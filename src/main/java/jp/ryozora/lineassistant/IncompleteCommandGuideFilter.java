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
                    "タスクを追加", "やることを続けて入力してね",
                    "例：タスク 資料を提出", "メモタスク操作", "#2E9B6B");
            case "メモ", "メモ追加" -> new Guide(
                    "メモを追加", "残したい内容を続けて入力してね",
                    "例：メモ 牛乳を買う", "メモタスク操作", "#D98AAA");
            case "習慣", "習慣追加" -> new Guide(
                    "習慣を追加", "続けたいことを入力してね",
                    "例：習慣 読書", "習慣成長操作", "#55A77E");
            case "支出", "支出追加", "支出記録" -> new Guide(
                    "支出を記録", "金額と内容を入力してね",
                    "例：支出 1200 昼食", "お金買い物操作", "#D88916");
            case "買い物", "買い物追加" -> new Guide(
                    "買い物を追加", "買うものを入力してね",
                    "例：買い物追加 ティッシュ", "お金買い物操作", "#E5A855");
            case "予定追加", "明日19時" -> new Guide(
                    "予定を追加", "日時と内容を一緒に入力してね",
                    "例：明日19時 歯医者", "予定操作", "#2E6FC4");
            case "予定変更" -> new Guide(
                    "予定を変更", "予定一覧から対象の「変更」を押してね",
                    "番号を覚える必要はないよ", "予定一覧", "#2E6FC4");
            case "予定削除" -> new Guide(
                    "予定を削除", "予定一覧から対象の「削除」を押してね",
                    "単発・繰り返しの範囲も選べるよ", "予定一覧", "#D96C75");
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
        bubble.put("body", vertical("#FCFDFE", List.of(
                text(guide.example(), "md", "bold", "#334E68"),
                text("内容を入力して送信してね", "sm", "regular", "#526D82"),
                button("← 戻る", guide.backMessage(), "#8E9CB3"),
                button("🏠 ホーム", "ホーム", "#8E9CB3")
        )));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "flex");
        message.put("altText", guide.title());
        message.put("contents", bubble);
        sendReply(replyToken, List.of(message));
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

    private record Guide(String title, String instruction, String example, String backMessage, String color) {}

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
