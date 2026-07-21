package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class LineWebhookController {
    private final LineProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String, List<String>> memos = new ConcurrentHashMap<>();

    public LineWebhookController(LineProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of("app", "my-line-assistant", "version", "0.1.0", "status", "running");
    }

    @PostMapping("/line/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "x-line-signature", required = false) String signature,
            @RequestBody String body
    ) {
        if (!validSignature(body, signature)) {
            return ResponseEntity.status(401).build();
        }

        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) continue;

                String replyToken = event.path("replyToken").asText();
                String text = event.path("message").path("text").asText();
                reply(replyToken, handle(userId, text));
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid LINE webhook", e);
        }
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank()
                || props.ownerUserId().equals(userId);
    }

    private String handle(String userId, String raw) {
        String text = raw == null ? "" : raw.strip();

        if (text.equals("ヘルプ") || text.equalsIgnoreCase("help") || text.equals("使い方")) {
            return "使えるコマンド\n"
                    + "・メモ 牛乳を買う\n"
                    + "・メモ一覧\n"
                    + "・メモ全削除\n"
                    + "・アプリ\n"
                    + "・時刻\n"
                    + "・ヘルプ";
        }

        if (text.startsWith("メモ ")) {
            String memo = text.substring(3).strip();
            if (memo.isBlank()) return "「メモ 牛乳を買う」のように送ってね。";
            memos.computeIfAbsent(userId, k ->
                    Collections.synchronizedList(new ArrayList<>())).add(memo);
            return "メモしたよ。\n・" + memo;
        }

        if (text.equals("メモ") || text.equals("メモ一覧")) {
            List<String> list = memos.getOrDefault(userId, List.of());
            if (list.isEmpty()) return "メモはまだないよ。";
            StringBuilder out = new StringBuilder("メモ一覧\n");
            for (int i = 0; i < list.size(); i++) {
                out.append(i + 1).append(". ").append(list.get(i)).append("\n");
            }
            return out.toString().stripTrailing();
        }

        if (text.equals("メモ全削除")) {
            memos.remove(userId);
            return "メモを全部削除したよ。";
        }

        if (text.equals("アプリ") || text.equals("ゲーム")) {
            return "自作アプリ一覧\n"
                    + "・宴会ゲーム集：URLを設定してください\n"
                    + "・国旗当てゲーム：URLを設定してください\n"
                    + "・エコ検定：URLを設定してください";
        }

        if (text.equals("時刻") || text.equals("時間")) {
            return ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
                    .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        }

        return "受け取ったよ：「" + text + "」\n\n「ヘルプ」と送ると使える機能を表示するよ。";
    }

    private boolean validSignature(String body, String received) {
        if (received == null || props.channelSecret() == null || props.channelSecret().isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.channelSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    received.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Signature verification failed", e);
        }
    }

    private void reply(String replyToken, String text) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "replyToken", replyToken,
                    "messages", List.of(Map.of("type", "text", "text", text))
            ));

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://api.line.me/v2/bot/message/reply"))
                    .header("Authorization", "Bearer " + props.channelAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "LINE API error: " + response.statusCode() + " " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Reply failed", e);
        }
    }
}
