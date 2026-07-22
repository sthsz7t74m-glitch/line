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
@Order(Ordered.HIGHEST_PRECEDENCE + 9)
public class RichInputGuideFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public RichInputGuideFilter(ObjectMapper mapper, LineProperties props) {
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
                Guide guide = guideFor(input);
                if (guide == null) continue;
                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
                replyGuide(event.path("replyToken").asText(), guide);
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

    private Guide guideFor(String input) {
        return switch (input) {
            case "予定追加ガイド" -> new Guide(
                    "予定を追加", "日時・繰り返し・通知を文章で指定できるよ", "#2E6FC4", "予定操作", "予定一覧",
                    List.of(
                            new Example("基本", "明日19時 歯医者"),
                            new Example("日付指定", "8/1 20時 会議"),
                            new Example("毎日", "毎日7時 朝の散歩"),
                            new Example("平日", "平日9時 朝会"),
                            new Example("曜日指定", "毎週火曜20時 ジム"),
                            new Example("通知指定", "明日19時 歯医者 30分前と10分前")
                    ));
            case "メモ追加ガイド" -> new Guide(
                    "メモを追加", "残したい内容を『メモ』に続けて送ってね", "#D98AAA", "メモタスク操作", "メモ一覧",
                    List.of(
                            new Example("短いメモ", "メモ 牛乳を買う"),
                            new Example("連絡先", "メモ 病院 03-1234-5678"),
                            new Example("覚え書き", "メモ 会議では予算案を確認する"),
                            new Example("あとで検索", "メモ パスワード変更は金曜日")
                    ));
            case "タスク追加ガイド" -> new Guide(
                    "タスクを追加", "やることを『タスク』に続けて送ってね", "#2E9B6B", "メモタスク操作", "タスク一覧",
                    List.of(
                            new Example("基本", "タスク 資料を提出"),
                            new Example("具体的に", "タスク 山田さんへ見積書を送る"),
                            new Example("買い物系", "タスク クリーニングを受け取る"),
                            new Example("連絡系", "タスク 病院へ予約の電話をする")
                    ));
            case "支出追加ガイド" -> new Guide(
                    "支出を記録", "内容・金額・日付はいろいろな順番で入力できるよ", "#D88916", "お金買い物操作", "支出一覧",
                    List.of(
                            new Example("内容＋金額", "昼食 1200円"),
                            new Example("金額＋内容", "支出 980 電車"),
                            new Example("今日指定", "今日 コンビニ 650円"),
                            new Example("昨日指定", "昨日 日用品 2300円"),
                            new Example("日付指定", "7/20 病院 3000円")
                    ));
            case "買い物追加ガイド" -> new Guide(
                    "買い物を追加", "買うものを『買い物』に続けて送ってね", "#E5A855", "お金買い物操作", "買い物一覧",
                    List.of(
                            new Example("1品", "買い物 ティッシュ"),
                            new Example("数量つき", "買い物 牛乳 2本"),
                            new Example("詳しく", "買い物 単3電池 8本入り"),
                            new Example("用途つき", "買い物 会議用の水 6本")
                    ));
            case "習慣追加ガイド" -> new Guide(
                    "習慣を追加", "曜日・頻度・通知時刻を指定できるよ", "#55A77E", "習慣成長操作", "今日の習慣",
                    List.of(
                            new Example("毎日", "習慣 読書 毎日"),
                            new Example("平日", "習慣 ストレッチ 平日"),
                            new Example("曜日指定", "習慣 筋トレ 月水金"),
                            new Example("曜日＋通知", "習慣 ゴミ出し 火土 8:00"),
                            new Example("毎日＋通知", "習慣 薬 毎日 21:00"),
                            new Example("通知なし", "習慣 日記 毎日")
                    ));
            default -> null;
        };
    }

    private void replyGuide(String replyToken, Guide guide) throws Exception {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(text("入力例", "sm", "bold", "#526D82"));
        for (Example example : guide.examples()) {
            body.add(exampleCard(example));
        }
        body.add(text("上の例を参考に、内容を入力して送信してね", "sm", "regular", "#526D82"));
        body.add(row(
                button("← 戻る", guide.backMessage(), "#8E9CB3"),
                button("一覧を見る", guide.listMessage(), guide.accent())
        ));
        body.add(button("🏠 ホーム", "ホーム", "#8E9CB3"));

        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical("#F3F6FA", List.of(
                text(guide.title(), "xl", "bold", "#243B53"),
                text(guide.instruction(), "sm", "regular", "#526D82")
        )));
        bubble.put("body", vertical("#FCFDFE", body));

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", guide.title());
        flex.put("contents", bubble);
        sendReply(replyToken, List.of(flex));
    }

    private Map<String, Object> exampleCard(Example example) {
        return vertical("#F5F7FA", List.of(
                text(example.label(), "xs", "bold", "#7B8794"),
                text(example.command(), "md", "bold", "#334E68")
        ));
    }

    private Map<String, Object> vertical(String color, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", color);
        box.put("paddingAll", "14px");
        box.put("cornerRadius", "12px");
        box.put("spacing", "sm");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> row(Map<String, Object> left, Map<String, Object> right) {
        return Map.of("type", "box", "layout", "horizontal", "spacing", "md", "contents", List.of(left, right));
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

    private void sendReply(String replyToken, List<Map<String, Object>> messages) throws Exception {
        String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", messages));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                .header("Authorization", "Bearer " + props.channelAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> result = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (result.statusCode() / 100 != 2) throw new IllegalStateException("LINE API error: HTTP " + result.statusCode());
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

    private record Guide(String title, String instruction, String accent, String backMessage,
                         String listMessage, List<Example> examples) { }
    private record Example(String label, String command) { }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
