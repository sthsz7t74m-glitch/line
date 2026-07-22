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
@Order(Ordered.HIGHEST_PRECEDENCE + 39)
public class CompactGrowthCalendarUiFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of(
            "カレンダー", "週間カレンダー",
            "プロフィール", "ステータス", "レベル", "経験値", "称号",
            "実績", "実績一覧", "バッジ"
    );

    private final ObjectMapper mapper;
    private final LineProperties props;
    private final BenlyCommandService commandService;
    private final RpgService rpgService;
    private final HttpClient client = HttpClient.newHttpClient();

    public CompactGrowthCalendarUiFilter(ObjectMapper mapper,
                                         LineProperties props,
                                         BenlyCommandService commandService,
                                         RpgService rpgService) {
        this.mapper = mapper;
        this.props = props;
        this.commandService = commandService;
        this.rpgService = rpgService;
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

                Map<String, Object> bubble;
                String altText;
                if (input.contains("カレンダー")) {
                    bubble = calendarBubble(commandService.handle(userId, input));
                    altText = "週間カレンダー";
                } else if (Set.of("実績", "実績一覧", "バッジ").contains(input)) {
                    bubble = achievementsBubble(rpgService.handle(userId, input));
                    altText = "実績一覧";
                } else {
                    bubble = profileBubble(rpgService.handle(userId, input));
                    altText = "プロフィール";
                }

                replyFlex(event.path("replyToken").asText(), altText, bubble);
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

    private Map<String, Object> calendarBubble(String raw) {
        List<String> lines = cleanLines(raw);
        List<Map<String, Object>> days = new ArrayList<>();

        String currentDate = null;
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            if (line.contains("カレンダー") || isDivider(line)) continue;
            if (line.matches("^\\d{1,2}/\\d{1,2}.*")) {
                if (currentDate != null) days.add(dayCard(currentDate, events));
                currentDate = line;
                events = new ArrayList<>();
            } else if (currentDate != null) {
                events.add(line.replaceFirst("^[・•]\\s*", ""));
            }
        }
        if (currentDate != null) days.add(dayCard(currentDate, events));

        if (days.isEmpty()) {
            days.add(text("今週の予定はまだないよ", "md", "regular", "#526D82"));
        }

        List<Map<String, Object>> body = new ArrayList<>(days);
        body.add(horizontal(List.of(
                button("予定一覧", "予定一覧", "#4E85D1"),
                button("予定追加", "予定追加", "#76B4E3")
        )));
        body.add(button("🏠 ホーム", "ホーム", "#8592A6"));

        return bubble(
                "週間カレンダー",
                "7日間をコンパクト表示",
                "#2E6FC4",
                "#E5EFFF",
                body
        );
    }

    private Map<String, Object> dayCard(String date, List<String> events) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(text(date, "md", "bold", "#2E6FC4"));
        if (events.isEmpty()) {
            content.add(text("予定なし", "xs", "regular", "#8A96A6"));
        } else {
            for (String event : events) {
                String color = event.contains("予定なし") ? "#8A96A6" : "#334E68";
                content.add(text(event, "sm", "regular", color));
            }
        }
        return groupBox("#F5F8FD", "10px", content);
    }

    private Map<String, Object> profileBubble(String raw) {
        List<String> lines = cleanLines(raw);
        String level = findStartsWith(lines, "レベル ", "レベル 1");
        String title = findStartsWith(lines, "称号", "称号「はじめての冒険者」");
        String totalExp = findStartsWith(lines, "累計経験値", "累計経験値 0");
        String progress = lines.stream().filter(v -> v.contains("■") || v.contains("□")).findFirst().orElse("");
        String remaining = findStartsWith(lines, "次のレベルまで", "");
        String unlocked = findStartsWith(lines, "解除した実績", "");

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(groupBox("#F4EEFF", "14px", List.of(
                text(level, "xxl", "bold", "#7957C7"),
                text(title, "md", "bold", "#4D426B"),
                text(totalExp, "sm", "regular", "#6D6287")
        )));
        if (!progress.isBlank()) {
            List<Map<String, Object>> progressBlock = new ArrayList<>();
            progressBlock.add(text(progress, "sm", "bold", "#7957C7"));
            if (!remaining.isBlank()) progressBlock.add(text(remaining, "xs", "regular", "#6D6287"));
            body.add(groupBox("#FAF8FF", "10px", progressBlock));
        }
        if (!unlocked.isBlank()) body.add(text(unlocked, "sm", "bold", "#5D4A89"));

        List<Map<String, Object>> stats = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("タスク完了") || line.startsWith("買い物完了")
                    || line.startsWith("登録した予定") || line.startsWith("保存中のメモ")) {
                stats.add(text(line, "xs", "regular", "#526D82"));
            }
        }
        if (!stats.isEmpty()) body.add(groupBox("#F5F7FB", "10px", stats));

        body.add(horizontal(List.of(
                button("実績", "実績一覧", "#9A7BD0"),
                button("ミッション", "今日のミッション", "#7957C7")
        )));
        body.add(button("🏠 ホーム", "ホーム", "#8592A6"));

        return bubble("プロフィール", "成長状況", "#7957C7", "#EFE7FF", body);
    }

    private Map<String, Object> achievementsBubble(String raw) {
        List<String> lines = cleanLines(raw);
        String summary = lines.stream().filter(v -> v.startsWith("実績一覧")).findFirst().orElse("実績一覧");
        List<Map<String, Object>> unlocked = new ArrayList<>();
        List<Map<String, Object>> locked = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("【解除】") && !line.startsWith("【未解除】")) continue;
            boolean done = line.startsWith("【解除】");
            String name = line.replace("【解除】", "").replace("【未解除】", "").strip();
            String description = i + 1 < lines.size() ? lines.get(i + 1) : "";
            Map<String, Object> card = achievementCard(name, description, done);
            if (done) unlocked.add(card); else locked.add(card);
        }

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(groupBox("#F4EEFF", "12px", List.of(
                text(summary, "xl", "bold", "#7957C7"),
                text("解除済みを上に表示", "xs", "regular", "#6D6287")
        )));

        if (!unlocked.isEmpty()) {
            body.add(text("解除済み", "sm", "bold", "#5B9E76"));
            body.addAll(unlocked);
        }
        if (!locked.isEmpty()) {
            body.add(text("未解除", "sm", "bold", "#8A96A6"));
            body.addAll(locked);
        }
        if (unlocked.isEmpty() && locked.isEmpty()) {
            body.add(text("実績情報はまだないよ", "md", "regular", "#526D82"));
        }

        body.add(horizontal(List.of(
                button("プロフィール", "プロフィール", "#7062AD"),
                button("ミッション", "今日のミッション", "#7957C7")
        )));
        body.add(button("🏠 ホーム", "ホーム", "#8592A6"));

        return bubble("実績", "解除状況", "#7957C7", "#EFE7FF", body);
    }

    private Map<String, Object> achievementCard(String name, String description, boolean unlocked) {
        String background = unlocked ? "#EEF8F2" : "#F5F6F8";
        String titleColor = unlocked ? "#3E805A" : "#7A8798";
        String marker = unlocked ? "✓ " : "○ ";
        return groupBox(background, "10px", List.of(
                text(marker + name, "sm", "bold", titleColor),
                text(description, "xxs", "regular", unlocked ? "#5D7767" : "#98A1AE")
        ));
    }

    private Map<String, Object> bubble(String title, String subtitle, String accent,
                                       String headerColor, List<Map<String, Object>> body) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical(headerColor, "12px", "xs", List.of(
                text(title, "xl", "bold", accent),
                text(subtitle, "xxs", "regular", "#6A788B")
        )));
        bubble.put("body", vertical("#FCFDFE", "12px", "sm", body));
        return bubble;
    }

    private Map<String, Object> groupBox(String background, String padding,
                                         List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("cornerRadius", "12px");
        box.put("paddingAll", padding);
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

    private List<String> cleanLines(String raw) {
        return raw == null ? List.of() : raw.lines().map(String::strip).filter(v -> !v.isBlank()).toList();
    }

    private String findStartsWith(List<String> lines, String prefix, String fallback) {
        return lines.stream().filter(v -> v.startsWith(prefix)).findFirst().orElse(fallback);
    }

    private boolean isDivider(String value) {
        return value.matches("^[━─ー_=\\-]{3,}$");
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        flex.put("quickReply", Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"),
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
