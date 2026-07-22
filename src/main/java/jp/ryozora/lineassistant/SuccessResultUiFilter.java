package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class SuccessResultUiFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final LineProperties props;
    private final BenlyCommandService commandService;
    private final HabitService habitService;
    private final ExpenseService expenseService;
    private final AdvancedScheduleService scheduleService;
    private final HttpClient client = HttpClient.newHttpClient();

    public SuccessResultUiFilter(ObjectMapper mapper,
                                 LineProperties props,
                                 BenlyCommandService commandService,
                                 HabitService habitService,
                                 ExpenseService expenseService,
                                 AdvancedScheduleService scheduleService) {
        this.mapper = mapper;
        this.props = props;
        this.commandService = commandService;
        this.habitService = habitService;
        this.expenseService = expenseService;
        this.scheduleService = scheduleService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedRequest wrapped = new CachedRequest(request, body);
        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;
                String input = normalize(event.path("message").path("text").asText());
                Action action = actionFor(input);
                if (action == null) continue;
                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
                String result = execute(userId, input, action);
                if (result == null || isFailure(result)) {
                    chain.doFilter(wrapped, response);
                    return;
                }
                replyFlex(event.path("replyToken").asText(), action.title(), successBubble(action, result));
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Normal webhook processing remains the fallback.
        }
        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private Action actionFor(String input) {
        if (input.startsWith("メモ ") && input.length() > 3) {
            return new Action("メモを保存したよ", "#D989AD", "#FBEAF2", "メモ一覧", "メモ追加", "記録メニュー");
        }
        if (input.startsWith("タスク ") && input.length() > 4) {
            return new Action("タスクを追加したよ", "#2E9B6B", "#E7F7F1", "タスク一覧", "タスク追加", "記録メニュー");
        }
        if (input.startsWith("買い物 ") && input.length() > 4) {
            return new Action("買い物に追加したよ", "#D88916", "#FFF2DE", "買い物一覧", "買い物追加", "お金メニュー");
        }
        if (input.startsWith("習慣 ") && input.length() > 3) {
            return new Action("習慣を追加したよ", "#7957C7", "#EFE7FF", "習慣一覧", "習慣追加", "成長メニュー");
        }
        if (input.startsWith("支出 ") && input.length() > 3) {
            return new Action("支出を記録したよ", "#D88916", "#FFF2DE", "支出一覧", "支出追加", "お金メニュー");
        }
        if (input.startsWith("予定 ") && input.length() > 3
                || input.startsWith("毎日") || input.startsWith("毎週")
                || input.startsWith("毎月") || input.startsWith("平日") || input.startsWith("土日")) {
            return new Action("予定を追加したよ", "#2E6FC4", "#E5EFFF", "予定一覧", "予定追加", "予定メニュー");
        }
        return null;
    }

    private String execute(String userId, String input, Action action) {
        if (action.listCommand().equals("メモ一覧") || action.listCommand().equals("タスク一覧")
                || action.listCommand().equals("買い物一覧")) {
            return commandService.handle(userId, input);
        }
        if (action.listCommand().equals("習慣一覧")) return habitService.handle(userId, input);
        if (action.listCommand().equals("支出一覧")) return expenseService.handle(userId, input);
        return scheduleService.handle(userId, input);
    }

    private boolean isFailure(String result) {
        String value = result == null ? "" : result;
        return value.contains("読み取れなかった") || value.contains("書いてな")
                || value.contains("見つからなかった") || value.startsWith("受け取ったよ：")
                || value.contains("例：") && !value.contains("追加した") && !value.contains("記録した");
    }

    private Map<String, Object> successBubble(Action action, String result) {
        List<Map<String, Object>> body = new ArrayList<>();
        List<String> lines = result.lines().map(String::strip).filter(v -> !v.isBlank()).toList();
        List<Map<String, Object>> summary = new ArrayList<>();
        int count = 0;
        for (String line : lines) {
            if (line.matches("^[━─ー_=\\-]{3,}$")) continue;
            if (count++ >= 8) break;
            summary.add(text(line, count == 1 ? "sm" : "xs", count == 1 ? "bold" : "regular",
                    count == 1 ? "#243B53" : "#526D82"));
        }
        body.add(group("#F8FAFC", summary.isEmpty()
                ? List.of(text("登録内容を保存したよ", "sm", "bold", "#243B53")) : summary));
        body.add(horizontal(List.of(
                primary("一覧を見る", action.listCommand(), action.accent()),
                secondary("もう1件追加", action.addCommand())
        )));
        body.add(horizontal(List.of(
                secondary("← 戻る", action.backCommand()),
                secondary("🏠 ホーム", "ホーム")
        )));
        return bubble(action.title(), "次の操作を選べるよ", action.accent(), action.headerColor(), body);
    }

    private Map<String, Object> bubble(String title, String subtitle, String accent,
                                       String headerColor, List<Map<String, Object>> body) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical(headerColor, "12px", "xs", List.of(
                text("✓ " + title, "lg", "bold", accent),
                text(subtitle, "xxs", "regular", "#718096")
        )));
        bubble.put("body", vertical("#FCFDFE", "12px", "sm", body));
        return bubble;
    }

    private Map<String, Object> group(String background, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("cornerRadius", "12px");
        box.put("paddingAll", "10px");
        box.put("spacing", "xs");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> vertical(String background, String padding, String spacing,
                                         List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("paddingAll", padding);
        box.put("spacing", spacing);
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> horizontal(List<Map<String, Object>> contents) {
        return Map.of("type", "box", "layout", "horizontal", "spacing", "sm", "contents", contents);
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        return Map.of("type", "text", "text", value, "size", size, "weight", weight,
                "color", color, "wrap", true, "flex", 1);
    }

    private Map<String, Object> primary(String label, String message, String color) {
        return button(label, message, "primary", color);
    }

    private Map<String, Object> secondary(String label, String message) {
        return button(label, message, "secondary", null);
    }

    private Map<String, Object> button(String label, String message, String style, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", style);
        button.put("height", "sm");
        button.put("flex", 1);
        button.put("adjustMode", "shrink-to-fit");
        if (color != null) button.put("color", color);
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        flex.put("quickReply", Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"),
                quick("最近使った", "最近使った"),
                quick("操作メニュー", "操作メニュー")
        )));
        sendReply(replyToken, List.of(flex));
    }

    private Map<String, Object> quick(String label, String message) {
        return Map.of("type", "action", "action", Map.of(
                "type", "message", "label", label, "text", message
        ));
    }

    private void sendReply(String replyToken, List<Map<String, Object>> messages) throws Exception {
        String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", messages));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                .header("Authorization", "Bearer " + props.channelAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LINE API error: HTTP " + response.statusCode());
        }
    }

    private boolean validSignature(byte[] body, String received) {
        if (received == null || props.channelSecret() == null || props.channelSecret().isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.channelSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank() || props.ownerUserId().equals(userId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record Action(String title, String accent, String headerColor,
                          String listCommand, String addCommand, String backCommand) { }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }
        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
