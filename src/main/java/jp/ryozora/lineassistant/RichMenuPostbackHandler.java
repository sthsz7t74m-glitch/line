package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RichMenuPostbackHandler {
    private final LineProperties props;
    private final ObjectMapper mapper;
    private final NotificationStore notificationStore;
    private final HttpClient client = HttpClient.newHttpClient();

    public RichMenuPostbackHandler(LineProperties props, ObjectMapper mapper,
                                   NotificationStore notificationStore) {
        this.props = props;
        this.mapper = mapper;
        this.notificationStore = notificationStore;
    }

    public boolean supports(String command) {
        return command != null && switch (command) {
            case "ホーム", "予定メニュー", "今日メニュー", "記録メニュー", "メモタスクメニュー",
                    "お金メニュー", "家計メニュー", "成長メニュー", "習慣メニュー", "通知設定" -> true;
            default -> false;
        };
    }

    public void handle(String userId, String replyToken, String command) {
        Map<String, Object> bubble;
        String altText;
        switch (command) {
            case "ホーム" -> {
                altText = "ベンリーのカテゴリホーム";
                bubble = homeBubble();
            }
            case "予定メニュー", "今日メニュー" -> {
                altText = "今日と予定のメニュー";
                bubble = scheduleMenuBubble();
            }
            case "記録メニュー", "メモタスクメニュー" -> {
                altText = "メモとタスクのメニュー";
                bubble = recordMenuBubble();
            }
            case "お金メニュー", "家計メニュー" -> {
                altText = "お金と買い物のメニュー";
                bubble = moneyMenuBubble();
            }
            case "成長メニュー", "習慣メニュー" -> {
                altText = "習慣と成長のメニュー";
                bubble = growthMenuBubble();
            }
            case "通知設定" -> {
                altText = "ベンリーの通知設定";
                bubble = notificationBubble(notificationStore.get(userId));
            }
            default -> throw new IllegalArgumentException("Unsupported postback command");
        }
        sendFlex(replyToken, altText, bubble);
    }

    private void sendFlex(String replyToken, String altText, Map<String, Object> bubble) {
        try {
            Map<String, Object> flex = new LinkedHashMap<>();
            flex.put("type", "flex");
            flex.put("altText", altText);
            flex.put("contents", bubble);
            Map<String, Object> payload = Map.of("replyToken", replyToken, "messages", List.of(flex));
            String json = mapper.writeValueAsString(payload);
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
            throw new IllegalStateException("Postback reply interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Postback reply failed", e);
        }
    }

    private Map<String, Object> homeBubble() {
        return bubble("#E8DEFF", "ベンリー ホーム", "使いたい種類を選んでね", "#493D69", List.of(
                buttonRow(button("今日・予定", "予定メニュー", "#6CA6E5"), button("メモ・タスク", "記録メニュー", "#78CDBB")),
                buttonRow(button("お金・買い物", "お金メニュー", "#66A98D"), button("習慣・成長", "成長メニュー", "#9A78D3")),
                buttonRow(button("通知設定", "通知設定", "#9BB8EA"), button("使い方", "ヘルプ", "#AEBBCF"))
        ));
    }

    private Map<String, Object> scheduleMenuBubble() {
        return bubble("#DDEBFF", "今日・予定", "今日の確認とスケジュール管理", "#334E68", List.of(
                buttonRow(button("今日まとめ", "今日のダッシュボード", "#80B8F0"), button("カレンダー", "カレンダー", "#6CA6E5")),
                buttonRow(button("予定一覧", "予定一覧", "#668FD8"), button("予定追加", "予定追加したい", "#8DCAF1")),
                buttonRow(button("今日の天気", "今日の天気", "#F2B95F"), button("通知設定", "通知設定", "#9BB8EA")),
                button("ホームへ", "ホーム", "#8E9CB3")
        ));
    }

    private Map<String, Object> recordMenuBubble() {
        return bubble("#DDF5EE", "メモ・タスク", "覚えておくことと、やること", "#315D50", List.of(
                buttonRow(button("メモ一覧", "メモ一覧", "#EFA6C6"), button("メモ追加", "メモ追加したい", "#E9B3CB")),
                buttonRow(button("タスク一覧", "タスク一覧", "#78CDBB"), button("タスク追加", "タスク追加したい", "#8AD5C5")),
                buttonRow(button("自分のデータ", "自分のデータ", "#AEBBCF"), button("統計", "統計", "#78B8A4")),
                button("ホームへ", "ホーム", "#8E9CB3")
        ));
    }

    private Map<String, Object> moneyMenuBubble() {
        return bubble("#DFF3E9", "お金・買い物", "支出と買うものをまとめて確認", "#315D50", List.of(
                buttonRow(button("家計簿", "家計簿", "#66A98D"), button("支出一覧", "支出一覧", "#5D9F88")),
                buttonRow(button("今日の支出", "今日いくら", "#72B399"), button("今月の支出", "今月いくら", "#62A78E")),
                buttonRow(button("カテゴリ別", "カテゴリ別", "#78B8A4"), button("買い物一覧", "買い物一覧", "#F0B878")),
                button("ホームへ", "ホーム", "#8E9CB3")
        ));
    }

    private Map<String, Object> growthMenuBubble() {
        return bubble("#EDE3FF", "習慣・成長", "続けることとベンリーの成長", "#4D426B", List.of(
                buttonRow(button("今日の習慣", "今日の習慣", "#55A77E"), button("習慣追加", "習慣追加したい", "#76B899")),
                buttonRow(button("ミッション", "今日のミッション", "#9A78D3"), button("プロフィール", "プロフィール", "#7E71BE")),
                buttonRow(button("実績", "実績一覧", "#A995D8"), button("習慣記録", "習慣統計", "#6FAF9D")),
                buttonRow(button("今週成績", "今週ランキング", "#E2A85D"), button("全体統計", "統計", "#78B8A4")),
                button("ホームへ", "ホーム", "#8E9CB3")
        ));
    }

    private Map<String, Object> notificationBubble(NotificationStore.Settings settings) {
        return bubble("#DDEBFF", "通知設定", "天気地域：" + settings.area(), "#334E68", List.of(
                toggleButton("朝のお知らせ", "朝", settings.morning(), "#E6A93A"),
                toggleButton("雨のお知らせ", "雨", settings.rain(), "#4A90D9"),
                toggleButton("予定のお知らせ", "予定", settings.schedule(), "#668FD8"),
                toggleButton("タスクのお知らせ", "タスク", settings.task(), "#4AAE9E"),
                toggleButton("夜のまとめ", "夜", settings.night(), "#7765B5"),
                button("通知の使い方", "通知ヘルプ", "#7F91B5")
        ));
    }

    private Map<String, Object> bubble(String headerColor, String title, String subtitle,
                                       String textColor, List<Map<String, Object>> contents) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box(headerColor, "18px", List.of(
                text(title, "xl", "bold", textColor, "center"),
                text(subtitle, "sm", "regular", textColor, "center")
        )));
        bubble.put("body", box("#FAFCFF", "14px", contents));
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
}
