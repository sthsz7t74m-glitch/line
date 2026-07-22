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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class CanonicalCategoryMenuFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of(
            "予定メニュー", "今日メニュー", "今日・予定", "今日と予定",
            "記録メニュー", "メモタスクメニュー", "メモ・タスク", "メモとタスク",
            "お金メニュー", "家計メニュー", "お金・買い物", "お金と買い物",
            "成長メニュー", "習慣メニュー", "習慣・成長", "習慣と成長"
    );

    private final ObjectMapper mapper;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public CanonicalCategoryMenuFilter(ObjectMapper mapper, LineProperties props) {
        this.mapper = mapper;
        this.props = props;
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

                sendMenu(event.path("replyToken").asText(), menuType(input));
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // 通常のWebhook処理へ渡す。
        }

        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private String menuType(String input) {
        if (input.contains("予定") || input.startsWith("今日")) return "schedule";
        if (input.contains("メモ") || input.contains("記録")) return "record";
        if (input.contains("お金") || input.contains("家計")) return "money";
        return "growth";
    }

    private void sendMenu(String replyToken, String type) throws Exception {
        Map<String, Object> bubble = switch (type) {
            case "schedule" -> category("🏠 ホーム ＞ 今日・予定", "予定と今日の確認", "#DDEBFF", "#2E6FC4", List.of(
                    row(button("今日まとめ", "今日のダッシュボード", "#6CA6E5"), button("予定一覧", "予定一覧", "#668FD8")),
                    row(button("カレンダー", "カレンダー", "#4E85D1"), button("予定追加", "予定追加", "#76B4E3")),
                    row(button("今日の天気", "今日の天気", "#E7A94E"), button("通知設定", "通知設定", "#7898CF"))
            ));
            case "record" -> category("🏠 ホーム ＞ メモ・タスク", "メモとやること", "#DDF5EE", "#2E9B6B", List.of(
                    row(button("タスク一覧", "タスク一覧", "#55B9A7"), button("タスク追加", "タスク追加", "#71C9B7")),
                    row(button("メモ一覧", "メモ一覧", "#D989AD"), button("メモ追加", "メモ追加", "#E4A5BF")),
                    row(button("自分のデータ", "自分のデータ", "#78869A"), button("統計", "統計", "#5AA78F"))
            ));
            case "money" -> category("🏠 ホーム ＞ お金・買い物", "支出と買い物", "#FFF0D9", "#D88916", List.of(
                    row(button("家計簿", "家計簿", "#D88916"), button("支出記録", "支出記録", "#C98A28")),
                    row(button("支出一覧", "支出一覧", "#B97D19"), button("買い物一覧", "買い物一覧", "#E7A94E")),
                    row(button("買い物追加", "買い物追加", "#EDBA70"), button("カテゴリ別", "カテゴリ別", "#D2A34E"))
            ));
            default -> category("🏠 ホーム ＞ 習慣・成長", "習慣と成長", "#EEE5FF", "#7957C7", List.of(
                    row(button("今日の習慣", "今日の習慣", "#55A77E"), button("習慣追加", "習慣追加", "#70B593")),
                    row(button("ミッション", "今日のミッション", "#7957C7"), button("プロフィール", "プロフィール", "#6D64B8")),
                    row(button("実績", "実績一覧", "#9A7BD0"), button("今週成績", "今週ランキング", "#D4A34B"))
            ));
        };

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "ベンリー カテゴリメニュー");
        flex.put("contents", bubble);
        String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", List.of(flex)));

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                .header("Authorization", "Bearer " + props.channelAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> result = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (result.statusCode() / 100 != 2) throw new IllegalStateException("LINE API error: " + result.statusCode());
    }

    private Map<String, Object> category(String title, String subtitle, String background, String accent,
                                         List<Map<String, Object>> rows) {
        java.util.ArrayList<Map<String, Object>> contents = new java.util.ArrayList<>(rows);
        contents.add(button("🏠 ホーム", "ホーム", "#78869A"));
        return Map.of(
                "type", "bubble",
                "size", "mega",
                "header", vertical(background, List.of(
                        text(title, "lg", "bold", accent),
                        text(subtitle, "sm", "regular", "#637083")
                )),
                "body", vertical("#FCFCFE", contents)
        );
    }

    private Map<String, Object> vertical(String background, List<Map<String, Object>> contents) {
        return Map.of("type", "box", "layout", "vertical", "backgroundColor", background,
                "paddingAll", "14px", "spacing", "md", "contents", contents);
    }

    private Map<String, Object> row(Map<String, Object> left, Map<String, Object> right) {
        return Map.of("type", "box", "layout", "horizontal", "spacing", "md", "contents", List.of(left, right));
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "button");
        result.put("style", "primary");
        result.put("height", "sm");
        result.put("color", color);
        result.put("flex", 1);
        result.put("adjustMode", "shrink-to-fit");
        result.put("action", Map.of("type", "message", "label", label, "text", message));
        return result;
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        return Map.of("type", "text", "text", value, "size", size, "weight", weight,
                "color", color, "align", "center", "wrap", true);
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
        return value == null ? "" : value.replace('\u3000', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }
    }
}
