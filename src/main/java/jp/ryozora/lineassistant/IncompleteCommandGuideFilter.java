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
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IncompleteCommandGuideFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public IncompleteCommandGuideFilter(ObjectMapper mapper, LineProperties props) {
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
            case "タスク", "タスク追加" -> new Guide(
                    "タスクを追加", "やることを入力して送信してね", List.of(
                    "基本：タスク 資料を提出",
                    "買い物：タスク 牛乳を買う",
                    "連絡：タスク 田中さんへ電話"
            ), "メモタスク操作", "入力した文章がそのままタスク名になるよ");
            case "メモ", "メモ追加" -> new Guide(
                    "メモを追加", "残したい内容を入力して送信してね", List.of(
                    "短いメモ：メモ 牛乳を買う",
                    "連絡先：メモ 山田さん 090-xxxx-xxxx",
                    "覚え書き：メモ 次回は資料を3部持参"
            ), "メモタスク操作", "あとから編集・お気に入り・タグ付けもできるよ");
            case "習慣", "習慣追加" -> new Guide(
                    "習慣を追加", "頻度や通知時刻も一緒に指定できるよ", List.of(
                    "毎日：習慣 読書 毎日",
                    "平日：習慣 ストレッチ 平日",
                    "曜日指定：習慣 筋トレ 月水金",
                    "曜日＋通知：習慣 ゴミ出し 火土 8:00",
                    "毎日＋通知：習慣 薬 毎日 21:00",
                    "通知なし：習慣 日記 毎日"
            ), "習慣成長操作", "曜日は「月水金」のように続けて書けるよ");
            case "支出", "支出追加", "支出記録" -> new Guide(
                    "支出を記録", "金額・内容・日付をいろいろな順番で入力できるよ", List.of(
                    "内容＋金額：昼食 1200円",
                    "金額＋内容：支出 980 電車",
                    "今日指定：今日 コンビニ 650円",
                    "昨日指定：昨日 スーパー 3200円",
                    "日付指定：7/20 本 1800円"
            ), "お金買い物操作", "分類は内容から自動で判定するよ");
            case "買い物", "買い物追加" -> new Guide(
                    "買い物を追加", "買うものを入力して送信してね", List.of(
                    "1品：買い物 ティッシュ",
                    "数量つき：買い物 牛乳 2本",
                    "詳しく：買い物 単4電池 8本入り"
            ), "お金買い物操作", "購入後は一覧のボタンから完了にできるよ");
            case "予定追加", "明日19時" -> new Guide(
                    "予定を追加", "単発・毎日・平日・曜日指定に対応しているよ", List.of(
                    "相対日時：明日19時 歯医者",
                    "日付指定：8/1 20時 会議",
                    "毎日：毎日7時 薬",
                    "平日：平日8時 朝会",
                    "曜日指定：毎週火曜20時 ジム",
                    "月指定：毎月25日18時 支払い"
            ), "予定操作", "「30分前」のように通知タイミングも追加できるよ");
            case "予定変更" -> new Guide(
                    "予定を変更", "予定一覧から対象の「変更」を押してね", List.of(
                    "単発予定はその予定だけ変更",
                    "繰り返し予定は対象範囲を選べる",
                    "番号を覚える必要はないよ"
            ), "予定一覧", "一覧のカードから操作するのが一番簡単だよ");
            case "予定削除" -> new Guide(
                    "予定を削除", "予定一覧から対象の「削除」を押してね", List.of(
                    "単発：この予定だけ削除",
                    "繰り返し：この回だけ",
                    "繰り返し：この回以降",
                    "繰り返し：シリーズ全部"
            ), "予定一覧", "全予定削除は一覧の一番下にあるよ");
            default -> null;
        };
    }

    private void replyGuide(String replyToken, Guide guide) throws Exception {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical("#F3F6FA", List.of(
                text(guide.title(), "xl", "bold", "#243B53"),
                text(guide.instruction(), "sm", "regular", "#526D82")
        )));

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(text("入力例", "sm", "bold", "#526D82"));
        for (String example : guide.examples()) {
            body.add(exampleBox(example));
        }
        body.add(text(guide.note(), "xs", "regular", "#6B7C93"));
        body.add(button("← 戻る", guide.backMessage(), "#8E9CB3"));
        body.add(button("🏠 ホーム", "ホーム", "#8E9CB3"));
        bubble.put("body", vertical("#FCFDFE", body));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "flex");
        message.put("altText", guide.title());
        message.put("contents", bubble);
        sendReply(replyToken, List.of(message));
    }

    private Map<String, Object> exampleBox(String value) {
        Map<String, Object> box = vertical("#F6F8FB", List.of(
                text(value, "sm", "bold", "#334E68")
        ));
        box.put("paddingAll", "10px");
        box.put("cornerRadius", "10px");
        return box;
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
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
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

    private record Guide(String title, String instruction, List<String> examples, String backMessage, String note) {}

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
                @Override public void setReadListener(ReadListener readListener) {}
                @Override public int read() { return input.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
