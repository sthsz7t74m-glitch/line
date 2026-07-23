package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LinePushService {
    private final LineProperties props;
    private final ObjectMapper mapper;
    private final NotificationHistoryStore history;
    private final HttpClient client = HttpClient.newHttpClient();

    public LinePushService(LineProperties props, ObjectMapper mapper, NotificationHistoryStore history) {
        this.props = props;
        this.mapper = mapper;
        this.history = history;
    }

    public void push(String userId, String text) {
        if (userId == null || userId.isBlank() || text == null || text.isBlank()) return;
        String safe = text.length() > 5000 ? text.substring(0, 5000) : text;
        send(userId, Map.of("type", "text", "text", safe));
        history.add(userId, "通知", firstLine(safe));
    }

    public void pushMorningBriefing(String userId, AiSecretaryService.MorningBriefing briefing) {
        WeatherService.Forecast forecast = briefing.forecast();
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(text("今日の予定　" + briefing.schedules().size() + "件", "md", "bold", "#3D5366", "start"));
        if (briefing.schedules().isEmpty()) {
            body.add(text("予定は入っていないよ", "sm", "regular", "#607889", "start"));
        } else {
            briefing.schedules().stream().limit(4).forEach(s -> body.add(text(
                    s.startsAt().format(DateTimeFormatter.ofPattern("H:mm")) + "　" + s.title(),
                    "sm", "regular", "#526D82", "start")));
        }
        body.add(separator());
        body.add(text("未完了タスク　" + briefing.tasks().size() + "件", "md", "bold", "#3D5366", "start"));
        briefing.tasks().stream().limit(3).forEach(t -> body.add(text("・" + t.text(), "sm", "regular", "#526D82", "start")));
        body.add(text("買い物　" + briefing.shopping().size() + "件", "md", "bold", "#3D5366", "start"));
        briefing.shopping().stream().limit(3).forEach(i -> body.add(text("・" + i.text(), "sm", "regular", "#526D82", "start")));
        body.add(separator());
        body.add(text(briefing.area() + "の天気", "md", "bold", "#3D5366", "start"));
        body.add(text("最高 " + Math.round(forecast.maxTemperature()) + "℃ / 最低 "
                + Math.round(forecast.minTemperature()) + "℃ / 雨 "
                + forecast.dailyRainProbability() + "%", "sm", "regular", "#526D82", "start"));
        if (!briefing.belongings().isEmpty()) {
            body.add(text("持ち物チェック", "md", "bold", "#3D5366", "start"));
            body.add(text(String.join("・", briefing.belongings()), "sm", "regular", "#526D82", "start"));
        }
        body.add(separator());
        body.add(text(briefing.comment(), "sm", "bold", "#6B5470", "start"));

        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#FFF0B8", "18px", List.of(
                text("おはよう！", "xl", "bold", "#5A4930", "center"),
                text("今日のブリーフィング", "md", "bold", "#75613F", "center")
        )));
        bubble.put("body", box("#FFFCF3", "16px", body));
        bubble.put("footer", box("#FFF6D9", "12px", List.of(
                buttonRow(button("今日の状況", "今日のダッシュボード", "#E4A947"),
                        button("おすすめ順", "今日は何からやればいい？", "#D99452")),
                button("忘れ物を確認", "忘れ物チェック", "#A995D8")
        )));

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "おはよう！今日の予定と天気をまとめたよ");
        flex.put("contents", bubble);
        send(userId, flex);
        history.add(userId, "朝のまとめ", "予定" + briefing.schedules().size() + "件・タスク"
                + briefing.tasks().size() + "件・買い物" + briefing.shopping().size() + "件");
    }

    public void pushRainAlert(String userId, String area, String rainTime, int probability,
                              String source, LocalDateTime fetchedAt) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#E5F3FF", "16px", List.of(
                text("雨のお知らせ", "xl", "bold", "#2E6FC4", "start"),
                text(area, "sm", "regular", "#617184", "start")
        )));
        bubble.put("body", box("#FCFDFE", "14px", List.of(
                card("#EEF6FF", List.of(
                        text(rainTime + "ごろから雨の可能性", "lg", "bold", "#2E6FC4", "start"),
                        text("降水確率　" + probability + "%", "md", "bold", "#334E68", "start")
                )),
                card("#FFF8E8", List.of(
                        text("傘を忘れずに！", "sm", "bold", "#72551E", "start")
                )),
                text("取得元：" + source + "　更新："
                                + fetchedAt.format(DateTimeFormatter.ofPattern("M/d H:mm")),
                        "xxs", "regular", "#8A96A6", "start")
        )));
        bubble.put("footer", box("#F2F7FC", "12px", List.of(
                buttonRow(button("今日の天気", "今日の天気", "#4F7FC7"),
                        button("通知設定", "通知設定", "#7898CF")),
                button("🏠 ホーム", "ホーム", "#8793A5")
        )));

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "雨のお知らせ：" + area + "で" + rainTime + "ごろから雨の可能性");
        flex.put("contents", bubble);
        send(userId, flex);
        history.add(userId, "雨", area + " / " + rainTime + "ごろ / 降水確率" + probability + "%");
    }

    public void pushNightSummary(String userId, int completedTasks, int gainedExperience, int tomorrowSchedules) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#EFE7FF", "16px", List.of(
                text("今日もお疲れさま！", "xl", "bold", "#7957C7", "center"),
                text("今日のまとめ", "sm", "regular", "#6D6287", "center")
        )));
        bubble.put("body", box("#FCFDFE", "14px", List.of(
                card("#F8F4FF", List.of(
                        text("完了タスク　" + completedTasks + "件", "lg", "bold", "#4D426B", "start"),
                        text("経験値　+" + gainedExperience, "md", "bold", "#7957C7", "start")
                )),
                card("#F5F8FD", List.of(
                        text("明日の予定　" + tomorrowSchedules + "件", "md", "bold", "#334E68", "start"),
                        text(tomorrowSchedules == 0 ? "明日は予定に余裕がありそう" : "明日の準備も少しだけ確認しよう",
                                "sm", "regular", "#617184", "start")
                ))
        )));
        bubble.put("footer", box("#F4F0FC", "12px", List.of(
                buttonRow(button("今日の状況", "今日のダッシュボード", "#7957C7"),
                        button("明日の予定", "予定一覧", "#6F8FC7")),
                button("🏠 ホーム", "ホーム", "#8793A5")
        )));

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "今日のまとめ：完了タスク" + completedTasks + "件、経験値+" + gainedExperience);
        flex.put("contents", bubble);
        send(userId, flex);
        history.add(userId, "夜のまとめ", "完了タスク" + completedTasks + "件・経験値+"
                + gainedExperience + "・明日の予定" + tomorrowSchedules + "件");
    }

    public void pushScheduleReminder(String userId, long scheduleId, String title,
                                     OffsetDateTime startsAt, int minutesBefore) {
        String timing = reminderTiming(minutesBefore, false);
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#DDEBFF", "18px", List.of(
                text("予定のお知らせ", "xl", "bold", "#334E68", "center"),
                text(timing, "md", "bold", "#526D82", "center")
        )));
        bubble.put("body", box("#FAFCFF", "16px", List.of(
                text(title, "xl", "bold", "#263746", "center"),
                text(startsAt.format(DateTimeFormatter.ofPattern("M月d日(E) H:mm")),
                        "md", "regular", "#526D82", "center"),
                text(minutesBefore >= 1440 ? "早めに準備しておこう" : "忘れ物はない？",
                        "sm", "regular", "#6B7F90", "center")
        )));
        bubble.put("footer", box("#EEF5FF", "12px", List.of(
                buttonRow(button("あと5分", "スヌーズ 5 " + scheduleId, "#71A7DD"),
                        button("あと10分", "スヌーズ 10 " + scheduleId, "#7EAEE8")),
                buttonRow(button("あと30分", "スヌーズ 30 " + scheduleId, "#8FC0E8"),
                        button("明日にする", "スヌーズ 明日 " + scheduleId, "#A995D8"))
        )));
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "予定のお知らせ：" + title + "（" + timing + "）");
        flex.put("contents", bubble);
        send(userId, flex);
        history.add(userId, "予定", title + " / " + startsAt.format(DateTimeFormatter.ofPattern("M/d H:mm")) + " / " + timing);
    }

    public void pushTaskReminder(String userId, long taskId, String title, String priority,
                                 OffsetDateTime dueAt, int minutesBefore) {
        String timing = reminderTiming(minutesBefore, true);
        String priorityText = switch (priority == null ? "MEDIUM" : priority) {
            case "HIGH" -> "優先度　高";
            case "LOW" -> "優先度　低";
            default -> "優先度　中";
        };

        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#DDF5EE", "18px", List.of(
                text("タスクのお知らせ", "xl", "bold", "#315D50", "center"),
                text(timing, "md", "bold", "#52776C", "center")
        )));
        bubble.put("body", box("#F8FFFC", "16px", List.of(
                text(title, "xl", "bold", "#274A40", "center"),
                text("期限　" + dueAt.format(DateTimeFormatter.ofPattern("M月d日(E) H:mm")),
                        "md", "regular", "#52776C", "center"),
                text(priorityText, "sm", "regular", "#6D817A", "center")
        )));
        bubble.put("footer", box("#EAF8F3", "12px", List.of(
                buttonRow(button("完了", "タスク完了ID " + taskId, "#4FA77E"),
                        button("1時間後", "タスク延期ID 60 " + taskId, "#6EADC2")),
                button("明日に延期", "タスク延期ID 明日 " + taskId, "#8D83C4")
        )));

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "タスクのお知らせ：" + title + "（" + timing + "）");
        flex.put("contents", bubble);
        send(userId, flex);
        history.add(userId, "タスク", title + " / " + dueAt.format(DateTimeFormatter.ofPattern("M/d H:mm")) + " / " + timing);
    }

    public void pushHabitReminder(String userId, long habitId, String name) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "kilo");
        bubble.put("header", box("#DFF5EA", "16px", List.of(
                text("習慣のお知らせ", "xl", "bold", "#315B49", "center"),
                text("今日も少しずつ続けよう", "sm", "regular", "#587767", "center")
        )));
        bubble.put("body", box("#FAFFFC", "18px", List.of(
                text(name, "xl", "bold", "#29483B", "center")
        )));
        bubble.put("footer", box("#EAF8F1", "12px", List.of(
                buttonRow(button("できた", "習慣達成ID " + habitId, "#55A77E"),
                        button("今日の一覧", "今日の習慣", "#7CB39A"))
        )));
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "習慣のお知らせ：" + name);
        flex.put("contents", bubble);
        send(userId, flex);
        history.add(userId, "習慣", name);
    }

    private String reminderTiming(int minutesBefore, boolean task) {
        if (minutesBefore == 0) return task ? "期限の時間になったよ" : "予定の時間になったよ";
        if (minutesBefore == 30 * 24 * 60) return "1か月前のお知らせ";
        if (minutesBefore % (7 * 24 * 60) == 0) return (minutesBefore / (7 * 24 * 60)) + "週間前のお知らせ";
        if (minutesBefore % (24 * 60) == 0) return (minutesBefore / (24 * 60)) + "日前のお知らせ";
        if (minutesBefore % 60 == 0) return (minutesBefore / 60) + "時間前のお知らせ";
        return minutesBefore + "分前のお知らせ";
    }

    private String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private void send(String userId, Map<String, Object> message) {
        if (userId == null || userId.isBlank()) return;
        try {
            String json = mapper.writeValueAsString(Map.of("to", userId, "messages", List.of(message)));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/push"))
                    .header("Authorization", "Bearer " + props.channelAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("LINE push failed: HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LINE push interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("LINE push failed", e);
        }
    }

    private Map<String, Object> box(String backgroundColor, String padding, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box"); box.put("layout", "vertical"); box.put("backgroundColor", backgroundColor);
        box.put("paddingAll", padding); box.put("spacing", "md"); box.put("contents", contents);
        return box;
    }

    private Map<String, Object> card(String backgroundColor, List<Map<String, Object>> contents) {
        Map<String, Object> value = box(backgroundColor, "12px", contents);
        value.put("cornerRadius", "12px");
        value.put("spacing", "xs");
        return value;
    }

    private Map<String, Object> separator() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "separator"); value.put("margin", "md"); value.put("color", "#E5DFD2");
        return value;
    }

    private Map<String, Object> buttonRow(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "box"); row.put("layout", "horizontal"); row.put("spacing", "sm");
        row.put("contents", List.of(left, right));
        return row;
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button"); button.put("style", "primary"); button.put("height", "sm");
        button.put("color", color); button.put("flex", 1); button.put("adjustMode", "shrink-to-fit");
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private Map<String, Object> text(String value, String size, String weight, String color, String align) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "text"); component.put("text", value); component.put("size", size);
        component.put("weight", weight); component.put("color", color); component.put("align", align);
        component.put("wrap", true);
        return component;
    }
}
