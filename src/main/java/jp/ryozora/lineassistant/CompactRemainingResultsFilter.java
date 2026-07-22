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
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 38)
public class CompactRemainingResultsFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of(
            "家計簿", "家計簿一覧", "支出一覧", "今日いくら", "今日の支出",
            "今月いくら", "今月の支出", "カテゴリ別", "支出カテゴリ",
            "今日の習慣", "習慣一覧", "習慣統計", "習慣の記録",
            "今日のダッシュボード", "今日のミッション", "今週ランキング",
            "自分のデータ", "統計", "買い物一覧"
    );

    private final ObjectMapper mapper;
    private final LineProperties props;
    private final ExpenseService expenseService;
    private final HabitService habitService;
    private final RpgService rpgService;
    private final AiSecretaryService aiSecretaryService;
    private final BenlyCommandService commandService;
    private final HttpClient client = HttpClient.newHttpClient();

    public CompactRemainingResultsFilter(ObjectMapper mapper,
                                         LineProperties props,
                                         ExpenseService expenseService,
                                         HabitService habitService,
                                         RpgService rpgService,
                                         AiSecretaryService aiSecretaryService,
                                         BenlyCommandService commandService) {
        this.mapper = mapper;
        this.props = props;
        this.expenseService = expenseService;
        this.habitService = habitService;
        this.rpgService = rpgService;
        this.aiSecretaryService = aiSecretaryService;
        this.commandService = commandService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedRequest wrapped = new CachedRequest(request, body);

        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;

                String input = normalize(event.path("message").path("text").asText());
                if (!COMMANDS.contains(input)) continue;
                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                String result = resultFor(userId, input);
                if (result == null || result.startsWith("受け取ったよ：")) continue;

                Style style = styleFor(input);
                replyFlex(event.path("replyToken").asText(), style.title(), bubble(style, result));
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Existing webhook processing remains the fallback.
        }

        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private String resultFor(String userId, String input) {
        if (expenseService.supports(input)) return expenseService.handle(userId, input);
        if (habitService.supports(input)) return habitService.handle(userId, input);
        if (rpgService.supports(input)) return rpgService.handle(userId, input);
        if (aiSecretaryService.supports(input)) return aiSecretaryService.handle(userId, input);
        return commandService.handle(userId, input);
    }

    private Style styleFor(String input) {
        if (Set.of("家計簿", "家計簿一覧", "支出一覧", "今日いくら", "今日の支出",
                "今月いくら", "今月の支出", "カテゴリ別", "支出カテゴリ").contains(input)) {
            return new Style("お金・家計簿", "#D88916", "#FFF2DE", "お金メニュー");
        }
        if (Set.of("今日の習慣", "習慣一覧", "習慣統計", "習慣の記録").contains(input)) {
            return new Style("習慣・成長", "#4F956D", "#E8F7EF", "成長メニュー");
        }
        if (Set.of("今日のミッション", "今週ランキング").contains(input)) {
            return new Style("成長記録", "#7957C7", "#EFE7FF", "成長メニュー");
        }
        if (input.equals("今日のダッシュボード")) {
            return new Style("今日のまとめ", "#2E6FC4", "#E5EFFF", "予定メニュー");
        }
        if (input.equals("買い物一覧")) {
            return new Style("買い物", "#D89A34", "#FFF3E1", "お金メニュー");
        }
        return new Style("ベンリー", "#526D82", "#F1F5F9", "ホーム");
    }

    private Map<String, Object> bubble(Style style, String raw) {
        List<String> lines = cleanLines(raw);
        String title = lines.isEmpty() ? style.title() : lines.get(0);
        List<String> contentLines = lines.size() <= 1 ? List.of() : lines.subList(1, lines.size());

        List<String> primary = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<String> meta = new ArrayList<>();

        for (String line : contentLines) {
            if (isInstruction(line)) {
                meta.add(line);
            } else if (isPrimary(line) && primary.size() < 4) {
                primary.add(line);
            } else if (details.size() < 12) {
                details.add(line);
            }
        }

        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical(style.headerColor(), "12px", "xs", List.of(
                text(title, "xl", "bold", style.accent()),
                text(style.title(), "xxs", "regular", "#6A788B")
        )));

        List<Map<String, Object>> body = new ArrayList<>();
        if (!primary.isEmpty()) {
            List<Map<String, Object>> main = new ArrayList<>();
            for (int i = 0; i < primary.size(); i++) {
                String size = i == 0 ? "lg" : "sm";
                String weight = i == 0 ? "bold" : "regular";
                main.add(text(primary.get(i), size, weight, i == 0 ? "#243B53" : "#526D82"));
            }
            body.add(groupBox(tint(style.headerColor()), "12px", main));
        }

        if (!details.isEmpty()) {
            List<Map<String, Object>> detailComponents = new ArrayList<>();
            int shown = 0;
            for (String line : details) {
                if (shown >= 10) break;
                boolean warning = line.contains("期限切れ") || line.contains("注意") || line.contains("未達成");
                boolean strong = line.startsWith("【") || line.startsWith("■") || line.startsWith("□")
                        || line.matches("^\\d+[.．].*");
                detailComponents.add(text(line, "xs", strong ? "bold" : "regular",
                        warning ? "#B54752" : "#526D82"));
                shown++;
            }
            body.add(groupBox("#F6F8FB", "10px", detailComponents));
        }

        if (!meta.isEmpty()) {
            body.add(text(String.join("　", meta.stream().limit(2).toList()),
                    "xxs", "regular", "#8A96A6"));
        }

        body.add(horizontal(List.of(
                button("関連", style.backMessage(), style.accent()),
                button("🏠 ホーム", "ホーム", "#8592A6")
        )));
        bubble.put("body", vertical("#FCFDFE", "12px", "sm", body));
        return bubble;
    }

    private boolean isPrimary(String line) {
        return line.contains("合計") || line.contains("達成率") || line.contains("レベル")
                || line.contains("経験値") || line.matches(".*\\d+件.*")
                || line.contains("今日") || line.contains("今月") || line.contains("連続");
    }

    private boolean isInstruction(String line) {
        return line.startsWith("例：") || line.startsWith("削除：") || line.startsWith("編集：")
                || line.startsWith("完了：") || line.startsWith("延期：")
                || line.contains("のように送") || line.contains("で確認できる");
    }

    private List<String> cleanLines(String raw) {
        return raw.lines().map(String::strip)
                .filter(v -> !v.isBlank())
                .filter(v -> !v.matches("^[━─ー_=\\-]{3,}$"))
                .toList();
    }

    private String tint(String headerColor) {
        return switch (headerColor) {
            case "#FFF2DE" -> "#FFF8EE";
            case "#E8F7EF" -> "#F2FAF6";
            case "#EFE7FF" -> "#F8F4FF";
            case "#E5EFFF" -> "#F1F6FF";
            case "#FFF3E1" -> "#FFF9F0";
            default -> "#F5F8FC";
        };
    }

    private Map<String, Object> groupBox(String background, String padding, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("cornerRadius", "12px");
        box.put("paddingAll", padding);
        box.put("spacing", "xs");
        box.put("contents", contents.isEmpty()
                ? List.of(text("表示できる情報はまだないよ", "sm", "regular", "#526D82"))
                : contents);
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
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "horizontal");
        box.put("spacing", "sm");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", value);
        text.put("size", size);
        text.put("weight", weight);
        text.put("color", color);
        text.put("wrap", true);
        return text;
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "primary");
        button.put("height", "sm");
        button.put("color", color);
        button.put("flex", 1);
        button.put("adjustMode", "shrink-to-fit");
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "flex");
        message.put("altText", altText);
        message.put("contents", bubble);
        message.put("quickReply", Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"),
                quick("操作メニュー", "操作メニュー")
        )));
        sendReply(replyToken, List.of(message));
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
        HttpResponse<Void> result = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (result.statusCode() / 100 != 2) {
            throw new IllegalStateException("LINE API error: HTTP " + result.statusCode());
        }
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank() || props.ownerUserId().equals(userId);
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

    private String normalize(String value) {
        return value == null ? "" : value.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record Style(String title, String accent, String headerColor, String backMessage) {}

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
