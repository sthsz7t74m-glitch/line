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
    private final HelpCommandService helpCommandService;
    private final SnoozeService snoozeService;
    private final AiSecretaryService aiSecretaryService;
    private final RpgService rpgService;
    private final DailyProgressService dailyProgressService;
    private final ExpenseService expenseService;
    private final HabitService habitService;
    private final HttpClient client = HttpClient.newHttpClient();

    public LineWebhookController(LineProperties props, ObjectMapper mapper,
                                 BenlyCommandService commandService,
                                 NaturalLanguageService naturalLanguageService,
                                 PrivacyCommandService privacyCommandService,
                                 NotificationCommandService notificationCommandService,
                                 WeatherCommandService weatherCommandService,
                                 AdvancedScheduleService advancedScheduleService,
                                 HelpCommandService helpCommandService,
                                 SnoozeService snoozeService,
                                 AiSecretaryService aiSecretaryService,
                                 RpgService rpgService,
                                 DailyProgressService dailyProgressService,
                                 ExpenseService expenseService,
                                 HabitService habitService) {
        this.props = props;
        this.mapper = mapper;
        this.commandService = commandService;
        this.naturalLanguageService = naturalLanguageService;
        this.privacyCommandService = privacyCommandService;
        this.notificationCommandService = notificationCommandService;
        this.weatherCommandService = weatherCommandService;
        this.advancedScheduleService = advancedScheduleService;
        this.helpCommandService = helpCommandService;
        this.snoozeService = snoozeService;
        this.aiSecretaryService = aiSecretaryService;
        this.rpgService = rpgService;
        this.dailyProgressService = dailyProgressService;
        this.expenseService = expenseService;
        this.habitService = habitService;
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.ofEntries(
                Map.entry("app", "benly"),
                Map.entry("version", "0.11.1"),
                Map.entry("status", "running"),
                Map.entry("storage", "postgresql"),
                Map.entry("naturalLanguage", "rule-based"),
                Map.entry("notifications", "enabled"),
                Map.entry("weather", "enabled"),
                Map.entry("rpg", "enabled"),
                Map.entry("dailyProgress", "enabled"),
                Map.entry("expenses", "enabled"),
                Map.entry("habits", "enabled")
        );
    }

    @PostMapping("/line/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "x-line-signature", required = false) String signature,
            @RequestBody String body) {
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
                if (isCategoryMenuCommand(input)) {
                    replyCategoryMenu(replyToken, input);
                    continue;
                }
                if (isHelpCommand(input)) {
                    replyHelp(replyToken);
                    continue;
                }
                if (helpCommandService.supports(input)) {
                    replyText(replyToken, helpCommandService.handle(input));
                    continue;
                }
                if (habitService.supports(input)) {
                    replyText(replyToken, habitService.handle(userId, input));
                    continue;
                }
                if (expenseService.supports(input)) {
                    replyText(replyToken, expenseService.handle(userId, input));
                    continue;
                }
                if (dailyProgressService.supports(input)) {
                    replyText(replyToken, dailyProgressService.handle(userId, input));
                    continue;
                }
                if (rpgService.supports(input)) {
                    replyText(replyToken, rpgService.handle(userId, input));
                    continue;
                }
                if (aiSecretaryService.supports(input)) {
                    replyText(replyToken, aiSecretaryService.handle(userId, input));
                    continue;
                }
                if (snoozeService.supports(input)) {
                    replyText(replyToken, snoozeService.handle(userId, input));
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
                        response = interpretation.type() == NaturalLanguageService.Type.SCHEDULE
                                ? advancedScheduleService.handle(userId, interpretation.command())
                                : interpretation.description() + "\n\n" + commandService.handle(userId, interpretation.command());
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

    private boolean isCategoryMenuCommand(String input) {
        return input.equals("予定メニュー") || input.equals("今日メニュー")
                || input.equals("記録メニュー") || input.equals("メモタスクメニュー")
                || input.equals("お金メニュー") || input.equals("家計メニュー")
                || input.equals("成長メニュー") || input.equals("習慣メニュー");
    }

    private boolean isHelpCommand(String input) {
        return input.equals("ヘルプ") || input.equals("使い方") || input.equals("コマンド")
                || input.equals("メニュー") || input.equalsIgnoreCase("help");
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank() || props.ownerUserId().equals(userId);
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
        sendFlex(replyToken, "ベンリーのカテゴリホーム", homeBubble());
    }

    private void replyCategoryMenu(String replyToken, String input) {
        if (input.equals("予定メニュー") || input.equals("今日メニュー")) {
            sendFlex(replyToken, "今日と予定のメニュー", scheduleMenuBubble());
        } else if (input.equals("記録メニュー") || input.equals("メモタスクメニュー")) {
            sendFlex(replyToken, "メモとタスクのメニュー", recordMenuBubble());
        } else if (input.equals("お金メニュー") || input.equals("家計メニュー")) {
            sendFlex(replyToken, "お金と買い物のメニュー", moneyMenuBubble());
        } else {
            sendFlex(replyToken, "習慣と成長のメニュー", growthMenuBubble());
        }
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
        bubble.put("header", box("#E8DEFF", "18px", List.of(
                text("ベンリー ホーム", "xl", "bold", "#493D69", "center"),
                text("使いたい種類を選んでね", "sm", "regular", "#6D6287", "center")
        )));
        bubble.put("body", box("#FCFAFF", "14px", List.of(
                buttonRow(button("今日・予定", "予定メニュー", "#6CA6E5"),
                        button("メモ・タスク", "記録メニュー", "#78CDBB")),
                buttonRow(button("お金・買い物", "お金メニュー", "#66A98D"),
                        button("習慣・成長", "成長メニュー", "#9A78D3")),
                buttonRow(button("通知設定", "通知設定", "#9BB8EA"),
                        button("使い方", "ヘルプ", "#AEBBCF"))
        )));
        bubble.put("footer", box("#F2EEFA", "10px", List.of(
                text("よく使う機能は下のボタンからも開けるよ", "xs", "regular", "#756C86", "center")
        )));
        return bubble;
    }

    private Map<String, Object> scheduleMenuBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#DDEBFF", "18px", List.of(
                text("今日・予定", "xl", "bold", "#334E68", "center"),
                text("今日の確認とスケジュール管理", "sm", "regular", "#526D82", "center")
        )));
        bubble.put("body", box("#FAFCFF", "14px", List.of(
                buttonRow(button("今日まとめ", "今日のダッシュボード", "#80B8F0"),
                        button("カレンダー", "カレンダー", "#6CA6E5")),
                buttonRow(button("予定一覧", "予定一覧", "#668FD8"),
                        button("予定追加", "明日19時 ", "#8DCAF1")),
                buttonRow(button("今日の天気", "今日の天気", "#F2B95F"),
                        button("通知設定", "通知設定", "#9BB8EA")),
                button("ホームへ", "ホーム", "#8E9CB3")
        )));
        return bubble;
    }

    private Map<String, Object> recordMenuBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#DDF5EE", "18px", List.of(
                text("メモ・タスク", "xl", "bold", "#315D50", "center"),
                text("覚えておくことと、やること", "sm", "regular", "#52776C", "center")
        )));
        bubble.put("body", box("#F8FFFC", "14px", List.of(
                buttonRow(button("メモ一覧", "メモ一覧", "#EFA6C6"),
                        button("メモ追加", "メモ ", "#E9B3CB")),
                buttonRow(button("タスク一覧", "タスク一覧", "#78CDBB"),
                        button("タスク追加", "タスク ", "#8AD5C5")),
                buttonRow(button("自分のデータ", "自分のデータ", "#AEBBCF"),
                        button("統計", "統計", "#78B8A4")),
                button("ホームへ", "ホーム", "#8E9CB3")
        )));
        return bubble;
    }

    private Map<String, Object> moneyMenuBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#DFF3E9", "18px", List.of(
                text("お金・買い物", "xl", "bold", "#315D50", "center"),
                text("支出と買うものをまとめて確認", "sm", "regular", "#52776C", "center")
        )));
        bubble.put("body", box("#FAFFFC", "14px", List.of(
                buttonRow(button("家計簿", "家計簿", "#66A98D"),
                        button("支出一覧", "支出一覧", "#5D9F88")),
                buttonRow(button("今日の支出", "今日いくら", "#72B399"),
                        button("今月の支出", "今月いくら", "#62A78E")),
                buttonRow(button("カテゴリ別", "カテゴリ別", "#78B8A4"),
                        button("買い物一覧", "買い物一覧", "#F0B878")),
                button("ホームへ", "ホーム", "#8E9CB3")
        )));
        return bubble;
    }

    private Map<String, Object> growthMenuBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#EDE3FF", "18px", List.of(
                text("習慣・成長", "xl", "bold", "#4D426B", "center"),
                text("続けることとベンリーの成長", "sm", "regular", "#6D6287", "center")
        )));
        bubble.put("body", box("#FCFAFF", "14px", List.of(
                buttonRow(button("今日の習慣", "今日の習慣", "#55A77E"),
                        button("習慣追加", "習慣 ", "#76B899")),
                buttonRow(button("ミッション", "今日のミッション", "#9A78D3"),
                        button("プロフィール", "プロフィール", "#7E71BE")),
                buttonRow(button("実績", "実績一覧", "#A995D8"),
                        button("習慣記録", "習慣統計", "#6FAF9D")),
                buttonRow(button("今週成績", "今週ランキング", "#E2A85D"),
                        button("全体統計", "統計", "#78B8A4")),
                button("ホームへ", "ホーム", "#8E9CB3")
        )));
        return bubble;
    }

    private Map<String, Object> helpBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#EDE3FF", "18px", List.of(
                text("ベンリーの使い方", "xl", "bold", "#4D426B", "center"),
                text("知りたい種類を選んでね", "sm", "regular", "#6D6287", "center")
        )));
        bubble.put("body", box("#FCFAFF", "12px", List.of(
                buttonRow(button("予定", "予定ヘルプ", "#7EAEE8"), button("天気", "天気ヘルプ", "#E7B45E")),
                buttonRow(button("メモ", "メモヘルプ", "#ECAFC4"), button("タスク", "タスクヘルプ", "#82CDBF")),
                buttonRow(button("買い物", "買い物ヘルプ", "#E9B86F"), button("家計簿", "家計簿ヘルプ", "#66A98D")),
                buttonRow(button("習慣", "習慣ヘルプ", "#55A77E"), button("通知", "通知ヘルプ", "#9AB9E6")),
                button("全コマンド", "コマンド一覧", "#BBA4DE"),
                button("ホームへ", "ホーム", "#8E9CB3")
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
        button.put("adjustMode", "shrink-to-fit");
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
                quickReply("ホーム", "ホーム"),
                quickReply("今日", "今日のダッシュボード"),
                quickReply("予定", "予定一覧"),
                quickReply("タスク", "タスク一覧"),
                quickReply("家計簿", "家計簿"),
                quickReply("習慣", "今日の習慣"),
                quickReply("ミッション", "今日のミッション"),
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
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
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
