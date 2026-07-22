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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CommandPaletteFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public CommandPaletteFilter(ObjectMapper mapper, LineProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI());
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

                String input = event.path("message").path("text").asText().strip();
                if (!supports(input)) continue;

                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                String replyToken = event.path("replyToken").asText();
                handle(replyToken, input);
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

    private boolean supports(String input) {
        return switch (input) {
            case "操作メニュー", "操作一覧", "コマンド一覧",
                 "予定操作", "メモタスク操作", "お金買い物操作", "習慣成長操作",
                 "予定追加ガイド", "メモ追加ガイド", "タスク追加ガイド",
                 "支出追加ガイド", "買い物追加ガイド", "習慣追加ガイド" -> true;
            default -> false;
        };
    }

    private void handle(String replyToken, String input) throws Exception {
        switch (input) {
            case "操作メニュー", "操作一覧", "コマンド一覧" ->
                    replyFlex(replyToken, "ベンリー操作メニュー", mainPalette());
            case "予定操作" -> replyFlex(replyToken, "予定の操作", schedulePalette());
            case "メモタスク操作" -> replyFlex(replyToken, "メモとタスクの操作", recordPalette());
            case "お金買い物操作" -> replyFlex(replyToken, "お金と買い物の操作", moneyPalette());
            case "習慣成長操作" -> replyFlex(replyToken, "習慣と成長の操作", growthPalette());
            case "予定追加ガイド" -> replyGuide(replyToken, "予定を追加", "日時と内容を送ってね", "例：明日19時 歯医者", "予定操作");
            case "メモ追加ガイド" -> replyGuide(replyToken, "メモを追加", "『メモ』に続けて内容を送ってね", "例：メモ 牛乳を買う", "メモタスク操作");
            case "タスク追加ガイド" -> replyGuide(replyToken, "タスクを追加", "『タスク』に続けて内容を送ってね", "例：タスク 資料を提出", "メモタスク操作");
            case "支出追加ガイド" -> replyGuide(replyToken, "支出を記録", "金額と内容を送ってね", "例：支出 1200 昼食", "お金買い物操作");
            case "買い物追加ガイド" -> replyGuide(replyToken, "買い物を追加", "買う物を送ってね", "例：買い物追加 ティッシュ", "お金買い物操作");
            case "習慣追加ガイド" -> replyGuide(replyToken, "習慣を追加", "続けたいことを送ってね", "例：習慣 読書", "習慣成長操作");
            default -> replyFlex(replyToken, "ベンリー操作メニュー", mainPalette());
        }
    }

    private Map<String, Object> mainPalette() {
        return bubble("#EDE3FF", "操作メニュー", "やりたいことの種類を選んでね", List.of(
                row(button("今日・予定", "予定操作", "#2E6FC4"), button("メモ・タスク", "メモタスク操作", "#2E9B6B")),
                row(button("お金・買い物", "お金買い物操作", "#D88916"), button("習慣・成長", "習慣成長操作", "#7957C7")),
                row(button("通知設定", "通知設定", "#567EC7"), button("使い方", "ヘルプ", "#7F91B5")),
                button("🏠 ホーム", "ホーム", "#8E9CB3")
        ));
    }

    private Map<String, Object> schedulePalette() {
        return bubble("#DDEBFF", "今日・予定", "確認・追加・設定をボタンで操作", List.of(
                row(button("今日まとめ", "今日のダッシュボード", "#80B8F0"), button("予定一覧", "予定一覧", "#668FD8")),
                row(button("予定を追加", "予定追加ガイド", "#8DCAF1"), button("カレンダー", "カレンダー", "#6CA6E5")),
                row(button("今日の天気", "今日の天気", "#E2A23E"), button("通知設定", "通知設定", "#7899D6")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private Map<String, Object> recordPalette() {
        return bubble("#DDF5EE", "メモ・タスク", "記録や完了操作をまとめたよ", List.of(
                row(button("メモ一覧", "メモ一覧", "#D98AAA"), button("メモを追加", "メモ追加ガイド", "#E9A9C3")),
                row(button("タスク一覧", "タスク一覧", "#4AAE9E"), button("タスクを追加", "タスク追加ガイド", "#72C8B8")),
                row(button("今日のタスク", "今日のタスク", "#5BAF9D"), button("統計", "統計", "#6FAF9D")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private Map<String, Object> moneyPalette() {
        return bubble("#FFF0D9", "お金・買い物", "記録と確認をまとめたよ", List.of(
                row(button("家計簿", "家計簿", "#D88916"), button("支出を記録", "支出追加ガイド", "#E0A23B")),
                row(button("支出一覧", "支出一覧", "#C98A28"), button("カテゴリ別", "カテゴリ別", "#C79745")),
                row(button("今日の支出", "今日いくら", "#D39A43"), button("今月の支出", "今月いくら", "#BD7F22")),
                row(button("買い物一覧", "買い物一覧", "#E5A855"), button("買い物を追加", "買い物追加ガイド", "#EDBA70")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private Map<String, Object> growthPalette() {
        return bubble("#EDE3FF", "習慣・成長", "続ける・確認する・振り返る", List.of(
                row(button("今日の習慣", "今日の習慣", "#55A77E"), button("習慣を追加", "習慣追加ガイド", "#76B899")),
                row(button("習慣統計", "習慣統計", "#6FAF9D"), button("ミッション", "今日のミッション", "#7957C7")),
                row(button("プロフィール", "プロフィール", "#7062AD"), button("実績", "実績一覧", "#9B83CE")),
                row(button("今週の成績", "今週ランキング", "#C8943D"), button("全体統計", "統計", "#78B8A4")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private void replyGuide(String replyToken, String title, String message, String example, String backMessage) throws Exception {
        Map<String, Object> guide = bubble("#F3F6FA", title, message, List.of(
                text(example, "md", "bold", "#334E68"),
                button("← 戻る", backMessage, "#8E9CB3"),
                button("🏠 ホーム", "ホーム", "#8E9CB3")
        ));
        replyFlex(replyToken, title, guide);
    }

    private Map<String, Object> bubble(String headerColor, String title, String subtitle,
                                       List<Map<String, Object>> bodyContents) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical(headerColor, List.of(
                text(title, "xl", "bold", "#243B53"),
                text(subtitle, "sm", "regular", "#526D82")
        )));
        bubble.put("body", vertical("#FCFDFE", bodyContents));
        return bubble;
    }

    private Map<String, Object> vertical(String color, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", color);
        box.put("paddingAll", "16px");
        box.put("spacing", "md");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> row(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "box");
        row.put("layout", "horizontal");
        row.put("spacing", "md");
        row.put("contents", List.of(left, right));
        return row;
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

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
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
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
