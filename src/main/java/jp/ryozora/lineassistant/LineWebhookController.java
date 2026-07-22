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
                "version", "0.4.0",
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
                String input = event.path("message").path("text").asText().strip();

                if (isHomeCommand(input)) {
                    replyFlex(replyToken);
                    continue;
                }

                String response = commandService.handle(userId, input);
                if (response.startsWith("受け取ったよ：")) {
                    NaturalLanguageService.Interpretation interpretation = naturalLanguageService.interpret(input);
                    if (interpretation != null) {
                        response = interpretation.description() + "\n\n"
                                + commandService.handle(userId, interpretation.command());
                    }
                }
                replyText(replyToken, response);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid LINE webhook", e);
        }
    }

    private boolean isHomeCommand(String input) {
        return input.equals("ホーム") || input.equals("ベンリー") || input.equals("トップ");
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

    private void replyText(String replyToken, String text) {
        String safeText = text.length() > 5000 ? text.substring(0, 5000) : text;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", safeText);
        message.put("quickReply", quickReplyMenu());
        sendReply(replyToken, List.of(message));
    }

    private void replyFlex(String replyToken) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "ベンリーのホームメニュー");
        flex.put("contents", homeBubble());
        flex.put("quickReply", quickReplyMenu());
        sendReply(replyToken, List.of(flex));
    }

    private Map<String, Object> homeBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#FFF2B8");
        header.put("paddingAll", "20px");
        header.put("contents", List.of(
                text("BENLY QUEST", "xs", "bold", "#9B7B2F", "center"),
                text("今日なにする？", "xl", "bold", "#4A3F35", "center"),
                text("使いたいボタンをタップしてね", "sm", "regular", "#75685C", "center")
        ));
        bubble.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("backgroundColor", "#FFFDF8");
        body.put("paddingAll", "16px");
        body.put("spacing", "md");
        body.put("contents", List.of(
                buttonRow(
                        button("📝 メモ", "メモ一覧", "#F5A9B8"),
                        button("✅ タスク", "タスク一覧", "#8FD3C7")
                ),
                buttonRow(
                        button("🛒 買い物", "買い物一覧", "#F5C27A"),
                        button("📅 予定", "今日の予定", "#91BCE8")
                ),
                buttonRow(
                        button("⭐ 経験値", "経験値", "#C7A7E8"),
                        button("🎮 アプリ", "アプリ", "#9FD19F")
                ),
                buttonRow(
                        button("➕ メモ追加", "メモ ", "#ECAFC4"),
                        button("➕ タスク追加", "タスク ", "#A7DDD4")
                )
        ));
        bubble.put("body", body);

        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("backgroundColor", "#F7F0E8");
        footer.put("paddingAll", "12px");
        footer.put("contents", List.of(
                text("困ったら「ヘルプ」", "xs", "regular", "#75685C", "center")
        ));
        bubble.put("footer", footer);
        return bubble;
    }

    private Map<String, Object> buttonRow(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "box");
        row.put("layout", "horizontal");
        row.put("spacing", "sm");
        row.put("contents", List.of(left, right));
        return row;
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "message");
        action.put("label", label);
        action.put("text", message);

        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "primary");
        button.put("height", "sm");
        button.put("color", color);
        button.put("flex", 1);
        button.put("action", action);
        return button;
    }

    private Map<String, Object> text(String value, String size, String weight, String color, String align) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "text");
        component.put("text", value);
        component.put("size", size);
        component.put("weight", weight);
        component.put("color", color);
        component.put("align", align);
        component.put("wrap", true);
        return component;
    }

    private Map<String, Object> quickReplyMenu() {
        return Map.of("items", List.of(
                quickReply("🏠 ホーム", "ホーム"),
                quickReply("📝 メモ", "メモ一覧"),
                quickReply("✅ タスク", "タスク一覧"),
                quickReply("🛒 買い物", "買い物一覧"),
                quickReply("📅 予定", "今日の予定"),
                quickReply("❓ ヘルプ", "ヘルプ")
        ));
    }

    private Map<String, Object> quickReply(String label, String text) {
        return Map.of(
                "type", "action",
                "action", Map.of("type", "message", "label", label, "text", text)
        );
    }

    private void sendReply(String replyToken, List<Map<String, Object>> messages) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "replyToken", replyToken,
                    "messages", messages
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
}
