package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class EnhancedMenuFilter extends OncePerRequestFilter {
    private static final Set<String> MENU_COMMANDS = Set.of(
            "ホーム", "ベンリー", "トップ", "メニュー",
            "予定メニュー", "今日メニュー", "今日・予定", "今日と予定", "予定",
            "記録メニュー", "メモタスクメニュー", "メモ・タスク", "メモとタスク", "記録",
            "お金メニュー", "家計メニュー", "お金・買い物", "お金と買い物", "お金",
            "成長メニュー", "習慣メニュー", "習慣・成長", "習慣と成長", "成長"
    );

    private final ObjectMapper mapper;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public EnhancedMenuFilter(ObjectMapper mapper, LineProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String signature = request.getHeader("x-line-signature");
        BodyRequest wrapped = new BodyRequest(request, body);

        if (!validSignature(body, signature)) {
            filterChain.doFilter(wrapped, response);
            return;
        }

        try {
            JsonNode root = mapper.readTree(body);
            boolean handled = false;
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;
                String input = normalize(event.path("message").path("text").asText());
                if (!MENU_COMMANDS.contains(input)) continue;
                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) continue;
                String replyToken = event.path("replyToken").asText();
                sendMenu(replyToken, canonical(input));
                handled = true;
            }
            if (handled) {
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Fall back to the existing webhook controller.
        }
        filterChain.doFilter(wrapped, response);
    }

    private String canonical(String input) {
        if (Set.of("予定メニュー", "今日メニュー", "今日・予定", "今日と予定", "予定").contains(input)) return "SCHEDULE";
        if (Set.of("記録メニュー", "メモタスクメニュー", "メモ・タスク", "メモとタスク", "記録").contains(input)) return "RECORD";
        if (Set.of("お金メニュー", "家計メニュー", "お金・買い物", "お金と買い物", "お金").contains(input)) return "MONEY";
        if (Set.of("成長メニュー", "習慣メニュー", "習慣・成長", "習慣と成長", "成長").contains(input)) return "GROWTH";
        return "HOME";
    }

    private void sendMenu(String replyToken, String type) throws Exception {
        Map<String, Object> bubble = switch (type) {
            case "SCHEDULE" -> scheduleBubble();
            case "RECORD" -> recordBubble();
            case "MONEY" -> moneyBubble();
            case "GROWTH" -> growthBubble();
            default -> homeBubble();
        };
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", switch (type) {
            case "SCHEDULE" -> "ホーム ＞ 今日・予定";
            case "RECORD" -> "ホーム ＞ メモ・タスク";
            case "MONEY" -> "ホーム ＞ お金・買い物";
            case "GROWTH" -> "ホーム ＞ 習慣・成長";
            default -> "ベンリー ホーム";
        });
        flex.put("contents", bubble);
        flex.put("quickReply", quickReply());

        String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", List.of(flex)));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                .header("Authorization", "Bearer " + props.channelAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("LINE API error: " + res.statusCode());
    }

    private Map<String, Object> homeBubble() {
        return bubble(
                header("ベンリー", "使いたい機能を選んでね", "#493D69", "#EDE3FF"),
                box("#FCFAFF", "12px", List.of(
                        sectionLabel("⭐ よく使う"),
                        row(button("今日まとめ", "今日のダッシュボード", "#6CA6E5"), button("タスク一覧", "タスク一覧", "#55B9A7")),
                        button("買い物一覧", "買い物一覧", "#E7A94E"),
                        separator(),
                        sectionLabel("カテゴリ"),
                        row(button("今日・予定", "予定メニュー", "#2E6FC4"), button("メモ・タスク", "記録メニュー", "#2E9B6B")),
                        row(button("お金・買い物", "お金メニュー", "#D88916"), button("習慣・成長", "成長メニュー", "#7957C7")),
                        row(button("通知", "通知設定", "#567EC7"), button("使い方", "ヘルプ", "#78869A"))
                ))
        );
    }

    private Map<String, Object> scheduleBubble() {
        return categoryBubble("🏠 ホーム ＞ 今日・予定", "予定と今日の確認", "#2E6FC4", "#DDEBFF", List.of(
                row(button("今日まとめ", "今日のダッシュボード", "#6CA6E5"), button("予定一覧", "予定一覧", "#668FD8")),
                row(button("カレンダー", "カレンダー", "#4E85D1"), button("予定追加", "明日19時 ", "#76B4E3")),
                row(button("今日の天気", "今日の天気", "#E7A94E"), button("通知設定", "通知設定", "#7898CF"))
        ));
    }

    private Map<String, Object> recordBubble() {
        return categoryBubble("🏠 ホーム ＞ メモ・タスク", "メモとやること", "#2E9B6B", "#DDF5EE", List.of(
                row(button("タスク一覧", "タスク一覧", "#55B9A7"), button("タスク追加", "タスク ", "#71C9B7")),
                row(button("メモ一覧", "メモ一覧", "#D989AD"), button("メモ追加", "メモ ", "#E4A5BF")),
                row(button("自分のデータ", "自分のデータ", "#78869A"), button("統計", "統計", "#5AA78F"))
        ));
    }

    private Map<String, Object> moneyBubble() {
        return categoryBubble("🏠 ホーム ＞ お金・買い物", "支出と買い物", "#D88916", "#FFF0D9", List.of(
                row(button("家計簿", "家計簿", "#D88916"), button("買い物一覧", "買い物一覧", "#E7A94E")),
                row(button("今日の支出", "今日いくら", "#C99532"), button("今月の支出", "今月いくら", "#B98527")),
                row(button("カテゴリ別", "カテゴリ別", "#D2A34E"), button("支出一覧", "支出一覧", "#B97D19"))
        ));
    }

    private Map<String, Object> growthBubble() {
        return categoryBubble("🏠 ホーム ＞ 習慣・成長", "習慣と成長", "#7957C7", "#EEE5FF", List.of(
                row(button("今日の習慣", "今日の習慣", "#55A77E"), button("習慣追加", "習慣 ", "#70B593")),
                row(button("ミッション", "今日のミッション", "#7957C7"), button("プロフィール", "プロフィール", "#6D64B8")),
                row(button("実績", "実績一覧", "#9A7BD0"), button("今週成績", "今週ランキング", "#D4A34B"))
        ));
    }

    private Map<String, Object> categoryBubble(String title, String subtitle, String accent, String background,
                                               List<Map<String, Object>> rows) {
        java.util.ArrayList<Map<String, Object>> contents = new java.util.ArrayList<>(rows);
        contents.add(button("🏠 ホーム", "ホーム", "#78869A"));
        return bubble(header(title, subtitle, accent, background), box("#FCFCFE", "12px", contents));
    }

    private Map<String, Object> bubble(Map<String, Object> header, Map<String, Object> body) {
        return Map.of("type", "bubble", "size", "mega", "header", header, "body", body);
    }

    private Map<String, Object> header(String title, String subtitle, String accent, String background) {
        return box(background, "14px", List.of(
                text(title, "lg", "bold", accent, "center"),
                text(subtitle, "xs", "regular", "#637083", "center")
        ));
    }

    private Map<String, Object> box(String background, String padding, List<Map<String, Object>> contents) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "box");
        map.put("layout", "vertical");
        map.put("backgroundColor", background);
        map.put("paddingAll", padding);
        map.put("spacing", "md");
        map.put("contents", contents);
        return map;
    }

    private Map<String, Object> row(Map<String, Object> left, Map<String, Object> right) {
        return Map.of("type", "box", "layout", "horizontal", "spacing", "md", "contents", List.of(left, right));
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> action = Map.of("type", "message", "label", label, "text", message);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "button");
        map.put("style", "primary");
        map.put("height", "sm");
        map.put("color", color);
        map.put("flex", 1);
        map.put("adjustMode", "shrink-to-fit");
        map.put("action", action);
        return map;
    }

    private Map<String, Object> text(String value, String size, String weight, String color, String align) {
        return Map.of("type", "text", "text", value, "size", size, "weight", weight,
                "color", color, "align", align, "wrap", true);
    }

    private Map<String, Object> sectionLabel(String value) {
        return text(value, "sm", "bold", "#586174", "start");
    }

    private Map<String, Object> separator() {
        return Map.of("type", "separator", "margin", "sm", "color", "#E5E8EE");
    }

    private Map<String, Object> quickReply() {
        return Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"), quick("今日", "今日のダッシュボード"),
                quick("タスク", "タスク一覧"), quick("買い物", "買い物一覧"),
                quick("通知", "通知設定")
        ));
    }

    private Map<String, Object> quick(String label, String text) {
        return Map.of("type", "action", "action", Map.of("type", "message", "label", label, "text", text));
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

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private BodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
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
