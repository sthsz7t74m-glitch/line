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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class GlobalUxNavigationFilter extends OncePerRequestFilter {
    private static final Set<String> SYSTEM_COMMANDS = Set.of(
            "戻る", "← 戻る", "前へ戻る", "最近使った", "最近の操作", "地域設定", "天気地域設定"
    );
    private static final Set<String> TRACKABLE = Set.of(
            "予定一覧", "カレンダー", "今日のダッシュボード", "今日の天気",
            "メモ一覧", "タスク一覧", "家計簿", "支出一覧", "買い物一覧",
            "今日の習慣", "習慣一覧", "プロフィール", "実績一覧", "通知設定"
    );

    private final ObjectMapper mapper;
    private final LineProperties props;
    private final NotificationStore notificationStore;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String, String> lastMenu = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> recent = new ConcurrentHashMap<>();

    public GlobalUxNavigationFilter(ObjectMapper mapper,
                                    LineProperties props,
                                    NotificationStore notificationStore) {
        this.mapper = mapper;
        this.props = props;
        this.notificationStore = notificationStore;
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
                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) continue;

                rememberContext(userId, input);
                rememberRecent(userId, input);

                if (!SYSTEM_COMMANDS.contains(input)) continue;
                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String replyToken = event.path("replyToken").asText();
                if (input.equals("戻る") || input.equals("← 戻る") || input.equals("前へ戻る")) {
                    replyBack(replyToken, userId);
                } else if (input.equals("最近使った") || input.equals("最近の操作")) {
                    replyFlex(replyToken, "最近使った機能", recentBubble(userId));
                } else {
                    replyFlex(replyToken, "天気の地域設定", regionBubble(userId));
                }
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Existing webhook pipeline remains the fallback.
        }

        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private void rememberContext(String userId, String input) {
        String menu = switch (input) {
            case "予定メニュー", "今日メニュー", "予定操作", "予定一覧", "カレンダー", "今日の天気" -> "予定メニュー";
            case "記録メニュー", "メモタスクメニュー", "メモタスク操作", "メモ一覧", "タスク一覧" -> "記録メニュー";
            case "お金メニュー", "家計メニュー", "お金買い物操作", "家計簿", "支出一覧", "買い物一覧" -> "お金メニュー";
            case "成長メニュー", "習慣メニュー", "習慣成長操作", "今日の習慣", "習慣一覧", "プロフィール", "実績一覧" -> "成長メニュー";
            default -> null;
        };
        if (menu != null) lastMenu.put(userId, menu);
    }

    private void rememberRecent(String userId, String input) {
        if (!TRACKABLE.contains(input)) return;
        Deque<String> history = recent.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.remove(input);
            history.addFirst(input);
            while (history.size() > 5) history.removeLast();
        }
    }

    private void replyBack(String replyToken, String userId) throws Exception {
        String destination = lastMenu.getOrDefault(userId, "ホーム");
        Map<String, Object> message = Map.of(
                "type", "text",
                "text", destination,
                "quickReply", quickReply()
        );
        sendReply(replyToken, List.of(message));
    }

    private Map<String, Object> recentBubble(String userId) {
        List<Map<String, Object>> body = new ArrayList<>();
        Deque<String> history = recent.get(userId);
        if (history == null || history.isEmpty()) {
            body.add(text("まだ利用履歴がないよ", "sm", "regular", "#718096"));
            body.add(text("機能を使うと、ここに最大5件表示されるよ", "xxs", "regular", "#98A1AE"));
        } else {
            synchronized (history) {
                for (String command : history) {
                    body.add(button(command, command, "#667EA8", false));
                }
            }
        }
        body.add(horizontal(List.of(
                button("操作メニュー", "操作メニュー", "#7B8798", false),
                button("🏠 ホーム", "ホーム", "#7B8798", false)
        )));
        return bubble("最近使った機能", "よく使う操作へすぐ戻れるよ", "#526D82", "#EEF2F7", body);
    }

    private Map<String, Object> regionBubble(String userId) {
        NotificationStore.Settings settings = notificationStore.get(userId);
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(group("#F4F8FD", List.of(
                text(settings.area(), "xl", "bold", "#2E6FC4"),
                text("緯度 " + round(settings.latitude()) + " / 経度 " + round(settings.longitude()),
                        "xxs", "regular", "#7A8798")
        )));
        body.add(text("天気と雨通知はこの地域を基準に取得するよ", "xs", "regular", "#526D82"));
        body.add(group("#FAFBFD", List.of(
                text("取得元：Open-Meteo", "xxs", "regular", "#8A96A6"),
                text("地域を変えるときは『地域変更 府中市』のように送ってね", "xxs", "regular", "#8A96A6")
        )));
        body.add(horizontal(List.of(
                button("今日の天気", "今日の天気", "#4E85D1", true),
                button("通知設定", "通知設定", "#7898CF", false)
        )));
        body.add(button("🏠 ホーム", "ホーム", "#8592A6", false));
        return bubble("天気の地域設定", "現在の基準地域", "#2E6FC4", "#E5F3FF", body);
    }

    private Map<String, Object> bubble(String title, String subtitle, String accent,
                                       String headerColor, List<Map<String, Object>> body) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical(headerColor, "12px", "xs", List.of(
                text(title, "lg", "bold", accent),
                text(subtitle, "xxs", "regular", "#6A788B")
        )));
        bubble.put("body", vertical("#FCFDFE", "12px", "sm", body));
        return bubble;
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

    private Map<String, Object> group(String background, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("cornerRadius", "12px");
        box.put("paddingAll", "12px");
        box.put("spacing", "xs");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        return Map.of("type", "text", "text", value, "size", size, "weight", weight,
                "color", color, "wrap", true);
    }

    private Map<String, Object> button(String label, String message, String color, boolean primary) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", primary ? "primary" : "secondary");
        button.put("height", "sm");
        if (primary) button.put("color", color);
        button.put("flex", 1);
        button.put("adjustMode", "shrink-to-fit");
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private Map<String, Object> quickReply() {
        return Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"),
                quick("最近使った", "最近使った"),
                quick("操作メニュー", "操作メニュー")
        ));
    }

    private Map<String, Object> quick(String label, String message) {
        return Map.of("type", "action", "action", Map.of(
                "type", "message", "label", label, "text", message
        ));
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        flex.put("quickReply", quickReply());
        sendReply(replyToken, List.of(flex));
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

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
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
