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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class LineWebhookController {
    private final LineProperties props;
    private final ObjectMapper mapper;
    private final BenlyCommandService commandService;
    private final NaturalLanguageService naturalLanguageService;
    private final HttpClient client = HttpClient.newHttpClient();

    public LineWebhookController(
            LineProperties props,
            ObjectMapper mapper,
            BenlyCommandService commandService,
            NaturalLanguageService naturalLanguageService
    ) {
        this.props = props;
        this.mapper = mapper;
        this.commandService = commandService;
        this.naturalLanguageService = naturalLanguageService;
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
                "app", "benly",
                "version", "0.3.0",
                "status", "running",
                "storage", "postgresql",
                "naturalLanguage", "rule-based"
        );
    }

    @PostMapping("/line/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "x-line-signature", required = false) String signature,
            @RequestBody String body
    ) {
        if (!validSignature(body, signature)) return ResponseEntity.status(401).build();

        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) continue;

                String replyToken = event.path("replyToken").asText();
                String input = event.path("message").path("text").asText();
                String response = commandService.handle(userId, input);

                if (response.startsWith("受け取ったよ：")) {
                    NaturalLanguageService.Interpretation interpretation = naturalLanguageService.interpret(input);
                    if (interpretation != null) {
                        response = interpretation.description() + "\n\n"
                                + commandService.handle(userId, interpretation.command());
                    }
                }
                reply(replyToken, response);
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

    private boolean validSignature(String body, String received) {
        if (received == null || props.channelSecret() == null || props.channelSecret().isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.channelSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Signature verification failed", e);
        }
    }

    private void reply(String replyToken, String text) {
        try {
            String safeText = text.length() > 5000 ? text.substring(0, 5000) : text;
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "text");
            message.put("text", safeText);
            message.put("quickReply", Map.of("items", List.of(
                    quickReply("📝 メモ", "メモ一覧"),
                    quickReply("➕ メモ追加", "メモ "),
                    quickReply("✅ タスク", "タスク一覧"),
                    quickReply("➕ タスク追加", "タスク "),
                    quickReply("🛒 買い物", "買い物一覧"),
                    quickReply("📅 今日の予定", "今日の予定"),
                    quickReply("⭐ 経験値", "経験値"),
                    quickReply("❓ ヘルプ", "ヘルプ")
            )));

            String json = mapper.writeValueAsString(Map.of(
                    "replyToken", replyToken,
                    "messages", List.of(message)
            ));

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                    .header("Authorization", "Bearer " + props.channelAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("LINE API error: " + response.statusCode() + " " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Reply failed", e);
        }
    }

    private Map<String, Object> quickReply(String label, String text) {
        return Map.of(
                "type", "action",
                "action", Map.of("type", "message", "label", label, "text", text)
        );
    }
}
