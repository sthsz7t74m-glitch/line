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
    private static final int MAX_WEBHOOK_BODY_BYTES = 256_000;

    private final LineProperties props;
    private final ObjectMapper mapper;
    private final BenlyCommandService commandService;
    private final NaturalLanguageService naturalLanguageService;
    private final PrivacyCommandService privacyCommandService;
    private final NotificationCommandService notificationCommandService;
    private final WeatherCommandService weatherCommandService;
    private final AdvancedScheduleService advancedScheduleService;
    private final HttpClient client = HttpClient.newHttpClient();

    public LineWebhookController(LineProperties props, ObjectMapper mapper,
                                 BenlyCommandService commandService,
                                 NaturalLanguageService naturalLanguageService,
                                 PrivacyCommandService privacyCommandService,
                                 NotificationCommandService notificationCommandService,
                                 WeatherCommandService weatherCommandService,
                                 AdvancedScheduleService advancedScheduleService) {
        this.props = props;
        this.mapper = mapper;
        this.commandService = commandService;
        this.naturalLanguageService = naturalLanguageService;
        this.privacyCommandService = privacyCommandService;
        this.notificationCommandService = notificationCommandService;
        this.weatherCommandService = weatherCommandService;
        this.advancedScheduleService = advancedScheduleService;
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
                "app", "benly",
                "version", "0.6.1",
                "status", "running",
                "storage", "postgresql",
                "naturalLanguage", "rule-based",
                "notifications", "enabled",
                "weather", "enabled",
                "recurringSchedules", "enabled"
        );
    }

    @PostMapping("/line/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "x-line-signature", required = false) String signature,
            @RequestBody String body
    ) {
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }
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
                if (input.length() > 1000) {
                    replyText(replyToken, "メッセージが長すぎるよ。1000文字以内に分けて送ってね！");
                    continue;
                }

                String privacyResponse = privacyCommandService.handle(userId, input);
                if (privacyResponse != null) {
                    replyText(replyToken, privacyResponse);
                    continue;
                }
                if (isHomeCommand(input)) {
                    replyHome(replyToken);
                    continue;
                }
                if (isHelpCommand(input)) {
                    replyHelp(replyToken);
                    continue;
                }
                if (notificationCommandService.isSettingsCommand(input)) {
                    replyNotificationSettings(replyToken, notificationCommandService.process(userId, input));
                    continue;
                }
                if (advancedScheduleService.supports(input)) {
                    String response = advancedScheduleService.handle(userId, input);
                    if (response != null) {
                        replyText(replyToken, response);
                        continue;
                    }
                }
                if (weatherCommandService.isWeatherCommand(input)) {
                    replyText(replyToken, weatherCommandService.handle(userId, input));
                    continue;
                }

                String response = commandService.handle(userId, input);
                if (response.startsWith("受け取ったよ：")) {
                    NaturalLanguageService.Interpretation interpretation = naturalLanguageService.interpret(input);
                    if (interpretation != null) {
                        if (interpretation.type() == NaturalLanguageService.Type.SCHEDULE) {
                            response = advancedScheduleService.handle(userId, interpretation.command());
                        } else {
                            response = interpretation.description() + "\n\n"
                                    + commandService.handle(userId, interpretation.command());
                        }
                    }
                }
                replyText(replyToken, response);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid LINE webhook payload");
        }
    }

    private boolean isHomeCommand(String input) {
        return input.equals("ホーム") || input.equals("ベンリー") || input.equals("トップ");
    }

    private boolean isHelpCommand(String input) {
        return input.equals("ヘルプ") || input.equals("使い方") || input.equals("コマンド")
                || input.equals("メニュー") || input.equalsIgnoreCase("help");
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
            throw new IllegalStateException("Signature verification failed");
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

    private void replyHome(String replyToken) {
        sendFlex(replyToken, "ベンリーのホームメニュー", homeBubble());
    }

    private void replyHelp(String replyToken) {
        sendFlex(replyToken, "ベンリーでできること", helpBubble());
    }

    private void replyNotificationSettings(String replyToken, NotificationStore.Settings settings) {
        sendFlex(replyToken, "ベンリーの通知設定", notificationBubble(settings));
    }

    private void sendFlex(String replyToken, String altText, Map<String, Object> bubble) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        flex.put("quickReply", quickReplyMenu());
        sendReply(replyToken, List.of(flex));
    }

    private Map<String, Object> homeBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#FFDDF0", "18px", List.of(
                text("ベンリー", "xl", "bold", "#503B49", "center"),
                text("きょうも、ちょっと便利に。", "md", "bold", "#765E6C", "center"),
                text("使いたい機能を選んでね", "sm", "regular", "#765E6C", "center")
        )));
        bubble.put("body", box("#FFF9FC", "12px", List.of(
                buttonRow(button("今日の予定", "今日の予定", "#80B8F0"), button("予定一覧", "予定一覧", "#6CA6E5")),
                buttonRow(button("予定を追加", "明日19時 ", "#8DCAF1"), button("繰り返し予定", "毎週月曜19時 ", "#9F9BE8")),
                buttonRow(button("今日の天気", "今日の天気", "#F2B95F"), button("明日の天気", "明日の天気", "#E9A95E")),
                buttonRow(button("メモ", "メモ一覧", "#EFA6C6"), button("タスク", "タスク一覧", "#78CDBB")),
                buttonRow(button("買い物", "買い物一覧", "#F0B878"), button("通知設定", "通知設定", "#9BB8EA")),
                buttonRow(button("使い方", "ヘルプ", "#A995D8"), button("個人データ", "プライバシー", "#AEBBCF"))
        )));
        bubble.put("footer", box("#F9EAF3", "10px", List.of(
                text("例：あさって19時 歯医者", "xs", "regular", "#765E6C", "center"),
                text("例：毎週月曜19時 ジム", "xs", "regular", "#765E6C", "center")
        )));
        return bubble;
    }

    private Map<String, Object> helpBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#EDE3FF", "18px", List.of(
                text("ベンリーの使い方", "xl", "bold", "#4D426B", "center"),
                text("知りたい機能を選んでね", "sm", "regular", "#6D6287", "center")
        )));
        bubble.put("body", box("#FCFAFF", "12px", List.of(
                buttonRow(button("予定", "予定ヘルプ", "#7EAEE8"), button("天気", "天気ヘルプ", "#E7B45E")),
                buttonRow(button("メモ", "メモヘルプ", "#ECAFC4"), button("タスク", "タスクヘルプ", "#82CDBF")),
                buttonRow(button("買い物", "買い物ヘルプ", "#E9B86F"), button("通知", "通知ヘルプ", "#9AB9E6")),
                buttonRow(button("個人データ", "プライバシー", "#AAB8CF"), button("ホームへ戻る", "ホーム", "#BBA4DE"))
        )));
        bubble.put("footer", box("#F2EEFA", "10px", List.of(
                text("自然な文章でも使えるよ", "xs", "bold", "#6D6287", "center"),
                text("牛乳ほしい / 明日傘いる？ / 毎日8時 薬", "xs", "regular", "#756C86", "center")
        )));
        return bubble;
    }

    private Map<String, Object> notificationBubble(NotificationStore.Settings settings) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#DDEBFF", "16px", List.of(
                text("通知設定", "xl", "bold", "#334E68", "center"),
                text("天気地域：" + settings.area(), "xs", "regular", "#526D82", "center")
        )));
        bubble.put("body", box("#FAFCFF", "12px", List.of(
                toggleButton("朝のお知らせ", "朝", settings.morning(), "#E6A93A"),
                toggleButton("雨のお知らせ", "雨", settings.rain(), "#4A90D9"),
                toggleButton("予定のお知らせ", "予定", settings.schedule(), "#668FD8"),
                toggleButton("タスクのお知らせ", "タスク", settings.task(), "#4AAE9E"),
                toggleButton("夜のまとめ", "夜", settings.night(), "#7765B5"),
                button("通知の使い方", "通知ヘルプ", "#7F91B5")
        )));
        return bubble;
    }

    private Map<String, Object> box(String backgroundColor, String padding, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", backgroundColor);
        box.put("paddingAll", padding);
        box.put("spacing", "md");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> toggleButton(String label, String type, boolean enabled, String enabledColor) {
        return button(label + "  " + (enabled ? "オン" : "オフ"), "通知切替 " + type,
                enabled ? enabledColor : "#8B949C");
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
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "primary");
        button.put("height", "sm");
        button.put("color", color);
        button.put("flex", 1);
        button.put("action", Map.of("type", "message", "label", label, "text", message));
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
                quickReply("ヘルプ", "ヘルプ"),
                quickReply("ホーム", "ホーム"),
                quickReply("天気", "今日の天気"),
                quickReply("予定", "予定一覧"),
                quickReply("メモ", "メモ一覧"),
                quickReply("タスク", "タスク一覧"),
                quickReply("買い物", "買い物一覧"),
                quickReply("通知", "通知設定")
        ));
    }

    private Map<String, Object> quickReply(String label, String text) {
        return Map.of("type", "action", "action", Map.of(
                "type", "message", "label", label, "text", text
        ));
    }

    private void sendReply(String replyToken, List<Map<String, Object>> messages) {
        try {
            String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", messages));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                    .header("Authorization", "Bearer " + props.channelAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("LINE API error: HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reply interrupted");
        } catch (Exception e) {
            throw new IllegalStateException("Reply failed");
        }
    }
}
