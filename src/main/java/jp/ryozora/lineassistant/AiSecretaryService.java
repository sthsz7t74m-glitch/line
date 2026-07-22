package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiSecretaryService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);

    private final BenlyStore store;
    private final JdbcTemplate jdbc;
    private final WeatherCommandService weather;
    private final NotificationStore notificationStore;
    private final WeatherService weatherService;

    public AiSecretaryService(BenlyStore store, JdbcTemplate jdbc, WeatherCommandService weather,
                              NotificationStore notificationStore, WeatherService weatherService) {
        this.store = store;
        this.jdbc = jdbc;
        this.weather = weather;
        this.notificationStore = notificationStore;
        this.weatherService = weatherService;
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        return text.matches(".*(今日何ある|今日なにある|今日の予定教えて|今日やること|やることある).*")
                || text.matches(".*(今週忙しい|今週の予定|今週何ある|今週なにある).*")
                || text.matches(".*(空いてる日|暇な日|予定ない日).*")
                || text.matches(".*(買い物.*残|買うものある|買い忘れ).*")
                || text.matches(".*(何か忘れてない|なにか忘れてない|忘れ物ある|忘れ物チェック|持ち物|今日のまとめ).*")
                || text.matches(".*(今日のダッシュボード|ダッシュボード|今日の状況).*")
                || text.matches(".*(何からやればいい|なにからやればいい|優先順位|おすすめ順).*")
                || text.matches(".*(今日空いてる|今日の空き時間|空いてる時間|暇な時間).*");
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        store.ensureUser(userId);
        if (text.contains("ダッシュボード") || text.contains("今日の状況")) return dashboard(userId);
        if (text.contains("何からやればいい") || text.contains("なにからやればいい")
                || text.contains("優先順位") || text.contains("おすすめ順")) return priorityAdvice(userId);
        if (text.contains("今日空いてる") || text.contains("今日の空き時間")
                || text.contains("空いてる時間") || text.contains("暇な時間")) return freeTimeToday(userId);
        if (text.contains("今週忙しい") || text.contains("今週の予定")
                || text.contains("今週何ある") || text.contains("今週なにある")) return weeklySummary(userId);
        if (text.contains("空いてる日") || text.contains("暇な日") || text.contains("予定ない日")) return freeDays(userId);
        if (text.contains("買い物") || text.contains("買うもの") || text.contains("買い忘れ")) return shoppingSummary(userId);
        if (text.contains("忘れてない") || text.contains("忘れ物") || text.contains("持ち物") || text.contains("今日のまとめ")) {
            return fullBriefing(userId);
        }
        return todayBriefing(userId);
    }

    public MorningBriefing morningBriefing(String userId) {
        store.ensureUser(userId);
        List<BenlyStore.ScheduleItem> schedules = store.listTodaySchedules(userId, OFFSET);
        List<BenlyStore.Item> tasks = store.listTasks(userId);
        List<BenlyStore.Item> shopping = store.listShoppingItems(userId);
        NotificationStore.Settings settings = notificationStore.get(userId);
        WeatherService.Forecast forecast = weatherService.fetch(settings.latitude(), settings.longitude());
        List<String> belongings = suggestedBelongings(schedules, forecast);
        String comment = smartComment(schedules.size(), tasks.size(), shopping.size(), forecast);
        return new MorningBriefing(settings.area(), schedules, tasks, shopping, forecast, belongings, comment);
    }

    private String dashboard(String userId) {
        List<BenlyStore.ScheduleItem> schedules = store.listTodaySchedules(userId, OFFSET);
        List<BenlyStore.Item> tasks = store.listTasks(userId);
        List<BenlyStore.Item> shopping = store.listShoppingItems(userId);
        int exp = store.experience(userId);
        int level = exp / 100 + 1;
        int progress = exp % 100;
        String gauge = "■".repeat(progress / 10) + "□".repeat(10 - progress / 10);
        String weatherLine;
        String comment;
        try {
            MorningBriefing briefing = morningBriefing(userId);
            WeatherService.Forecast forecast = briefing.forecast();
            weatherLine = "最高 " + Math.round(forecast.maxTemperature()) + "℃ / 最低 "
                    + Math.round(forecast.minTemperature()) + "℃ / 雨 " + forecast.dailyRainProbability() + "%";
            comment = briefing.comment();
        } catch (RuntimeException e) {
            weatherLine = "天気情報は一時取得できません";
            comment = dailyComment(schedules.size(), tasks.size(), shopping.size());
        }
        return """
                今日のダッシュボード
                ━━━━━━━━━━
                予定　%d件
                タスク　%d件
                買い物　%d件
                天気　%s

                レベル %d
                %s　%d/100

                %s
                """.formatted(schedules.size(), tasks.size(), shopping.size(), weatherLine,
                level, gauge, progress, comment).strip();
    }

    private String priorityAdvice(String userId) {
        List<BenlyStore.ScheduleItem> schedules = store.listTodaySchedules(userId, OFFSET);
        List<BenlyStore.Item> tasks = store.listTasks(userId);
        List<BenlyStore.Item> shopping = store.listShoppingItems(userId);
        ZonedDateTime now = ZonedDateTime.now(TOKYO);
        List<String> recommendations = new ArrayList<>();
        schedules.stream().filter(s -> s.startsAt().isAfter(now.toOffsetDateTime())).limit(2)
                .forEach(s -> recommendations.add(s.startsAt().format(DateTimeFormatter.ofPattern("H:mm"))
                        + "の予定「" + s.title() + "」に備える"));
        tasks.stream().limit(3).forEach(t -> recommendations.add("タスク「" + t.text() + "」を進める"));
        shopping.stream().limit(2).forEach(i -> recommendations.add("買い物「" + i.text() + "」を済ませる"));
        if (recommendations.isEmpty()) return "急いで片づけるものはなさそう。今日は自由に使える日やね！";
        StringBuilder out = new StringBuilder("今日のおすすめ順\n\n");
        for (int i = 0; i < Math.min(5, recommendations.size()); i++) {
            out.append(i + 1).append(".　").append(recommendations.get(i)).append("\n");
        }
        return out.append("\nまず1つだけ終わらせると、その後がかなり楽になるよ。")
                .toString().stripTrailing();
    }

    private String freeTimeToday(String userId) {
        List<BenlyStore.ScheduleItem> schedules = store.listTodaySchedules(userId, OFFSET).stream()
                .filter(s -> !s.startsAt().isBefore(OffsetDateTime.now(OFFSET))).toList();
        LocalDate today = LocalDate.now(TOKYO);
        LocalDateTime cursor = LocalDateTime.of(today, LocalTime.of(8, 0));
        LocalDateTime now = LocalDateTime.now(TOKYO).withSecond(0).withNano(0);
        if (now.isAfter(cursor)) cursor = roundUpToQuarter(now);
        LocalDateTime end = LocalDateTime.of(today, LocalTime.of(22, 0));
        List<String> slots = new ArrayList<>();
        for (BenlyStore.ScheduleItem schedule : schedules) {
            LocalDateTime start = schedule.startsAt().atZoneSameInstant(TOKYO).toLocalDateTime();
            if (start.isAfter(cursor.plusMinutes(29))) slots.add(formatSlot(cursor, start));
            LocalDateTime assumedEnd = start.plusHours(1);
            if (assumedEnd.isAfter(cursor)) cursor = assumedEnd;
        }
        if (end.isAfter(cursor.plusMinutes(29))) slots.add(formatSlot(cursor, end));
        if (slots.isEmpty()) return "今日はまとまった空き時間が少なそう。予定の間に短く休憩を入れてね。";
        StringBuilder out = new StringBuilder("今日の空き時間（予定は1時間として計算）\n\n");
        slots.stream().limit(6).forEach(slot -> out.append("・").append(slot).append("\n"));
        return out.append("\n30分以上空いている時間を表示しているよ。").toString().stripTrailing();
    }

    private LocalDateTime roundUpToQuarter(LocalDateTime value) {
        int remainder = value.getMinute() % 15;
        return remainder == 0 ? value : value.plusMinutes(15 - remainder).withSecond(0).withNano(0);
    }

    private String formatSlot(LocalDateTime start, LocalDateTime end) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("H:mm");
        return start.format(format) + "〜" + end.format(format);
    }

    private String dailyComment(int schedules, int tasks, int shopping) {
        int load = schedules * 2 + tasks + Math.min(shopping, 3);
        if (load >= 10) return "今日はかなり盛りだくさん。全部ではなく、重要な3つに絞るのがおすすめ！";
        if (load >= 6) return "ほどよく忙しい日。予定の前後に少し余裕を残しておこう。";
        if (load >= 2) return "無理のない一日になりそう。1つずつ片づけていこう！";
        return "今日は余白が多め。休むか、新しいことを始めるチャンス！";
    }

    private String smartComment(int schedules, int tasks, int shopping, WeatherService.Forecast forecast) {
        if (forecast.dailyRainProbability() >= 60 && shopping > 0) {
            return "雨が強くなる前に買い物を済ませると動きやすそう！";
        }
        if (forecast.maxTemperature() >= 33) {
            return "かなり暑くなりそう。予定を詰めすぎず、水分と休憩を優先しよう。";
        }
        if (forecast.minTemperature() <= 8) {
            return "朝晩は冷えそう。上着を忘れず、早めに準備しておこう。";
        }
        if (schedules >= 4) return "予定が多い日。移動時間を多めに見て、早めの行動がおすすめ！";
        if (tasks >= 4) return "タスクが多め。午前中に一番重いものを1つ終わらせると楽になるよ。";
        if (shopping > 0 && schedules <= 2) return "今日は予定が少なめ。帰りに買い物をまとめると効率よさそう！";
        return dailyComment(schedules, tasks, shopping);
    }

    private List<String> suggestedBelongings(List<BenlyStore.ScheduleItem> schedules,
                                             WeatherService.Forecast forecast) {
        Set<String> items = new LinkedHashSet<>();
        for (BenlyStore.ScheduleItem schedule : schedules) {
            String title = schedule.title().toLowerCase(Locale.ROOT);
            if (containsAny(title, "ジム", "筋トレ", "運動", "スポーツ")) {
                items.add("運動着"); items.add("タオル"); items.add("飲み物");
            }
            if (containsAny(title, "出張", "旅行", "ホテル", "飛行機", "新幹線")) {
                items.add("財布"); items.add("身分証"); items.add("充電器");
            }
            if (containsAny(title, "映画", "ライブ", "コンサート", "観劇")) {
                items.add("チケット"); items.add("財布");
            }
            if (containsAny(title, "歯医者", "病院", "診察", "クリニック")) {
                items.add("診察券"); items.add("保険証・マイナ保険証");
            }
            if (containsAny(title, "会議", "打合せ", "打ち合わせ", "商談")) {
                items.add("資料"); items.add("筆記用具");
            }
            if (containsAny(title, "学校", "授業", "講義", "試験")) {
                items.add("筆記用具"); items.add("必要な教材");
            }
        }
        if (forecast.dailyRainProbability() >= 40 || forecast.rainSoon()) items.add("傘");
        if (forecast.maxTemperature() >= 28) items.add("飲み物");
        if (forecast.minTemperature() <= 10) items.add("上着");
        return new ArrayList<>(items);
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private String todayBriefing(String userId) {
        List<BenlyStore.ScheduleItem> schedules = store.listTodaySchedules(userId, OFFSET);
        List<BenlyStore.Item> tasks = store.listTasks(userId);
        StringBuilder out = new StringBuilder("今日の秘書メモ\n\n");
        if (schedules.isEmpty()) out.append("予定は入ってないよ。\n");
        else {
            out.append("予定は").append(schedules.size()).append("件あるよ。\n");
            schedules.stream().limit(8).forEach(s -> out.append("・")
                    .append(s.startsAt().format(DateTimeFormatter.ofPattern("H:mm")))
                    .append("　").append(s.title()).append("\n"));
        }
        out.append("\n未完了タスクは").append(tasks.size()).append("件");
        if (tasks.isEmpty()) return out.append("。今日は身軽やね！").toString();
        out.append("。\n");
        tasks.stream().limit(5).forEach(t -> out.append("・").append(t.text()).append("\n"));
        return out.toString().stripTrailing();
    }

    private String weeklySummary(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        LocalDate endDate = today.plusDays(7);
        List<Schedule> schedules = jdbc.query("""
                select title, starts_at from schedules
                where line_user_id=? and starts_at>=? and starts_at<?
                order by starts_at
                """, (rs, i) -> new Schedule(rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atZone(TOKYO).toLocalDateTime()), userId,
                Timestamp.from(today.atStartOfDay(TOKYO).toInstant()),
                Timestamp.from(endDate.atStartOfDay(TOKYO).toInstant()));
        StringBuilder out = new StringBuilder("これから7日間の予定\n\n");
        if (schedules.isEmpty()) return out.append("予定はゼロ。かなりゆったりした週やね！").toString();
        for (int i = 0; i < 7; i++) {
            LocalDate date = today.plusDays(i);
            long count = schedules.stream().filter(s -> s.at().toLocalDate().equals(date)).count();
            out.append(date.format(DateTimeFormatter.ofPattern("M/d"))).append("（")
                    .append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE))
                    .append("）　").append(count).append("件\n");
        }
        long busiest = schedules.stream().map(s -> s.at().toLocalDate()).distinct()
                .mapToLong(d -> schedules.stream().filter(s -> s.at().toLocalDate().equals(d)).count())
                .max().orElse(0);
        out.append("\n合計").append(schedules.size()).append("件。");
        out.append(busiest >= 4 ? "予定が多い日があるから、少し余裕を見て動くのがおすすめ。"
                : busiest >= 2 ? "ほどよく予定が入ってる週やね。" : "比較的ゆったりした週やね。");
        return out.toString();
    }

    private String freeDays(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        LocalDate end = today.plusDays(7);
        List<LocalDate> busy = jdbc.query("""
                select distinct starts_at from schedules
                where line_user_id=? and starts_at>=? and starts_at<?
                """, (rs, i) -> rs.getTimestamp("starts_at").toInstant().atZone(TOKYO).toLocalDate(), userId,
                Timestamp.from(today.atStartOfDay(TOKYO).toInstant()),
                Timestamp.from(end.atStartOfDay(TOKYO).toInstant()));
        StringBuilder out = new StringBuilder("これから7日間で予定がない日\n\n");
        int count = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate date = today.plusDays(i);
            if (!busy.contains(date)) {
                out.append("・").append(date.format(DateTimeFormatter.ofPattern("M/d"))).append("（")
                        .append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE))
                        .append("）\n");
                count++;
            }
        }
        return count == 0 ? "これから7日間は毎日予定が入ってるよ。なかなか忙しい！"
                : out.toString().stripTrailing();
    }

    private String shoppingSummary(String userId) {
        List<BenlyStore.Item> items = store.listShoppingItems(userId);
        if (items.isEmpty()) return "買い物リストは空っぽ。今のところ買い忘れはなさそう！";
        StringBuilder out = new StringBuilder("まだ買っていないものが").append(items.size()).append("件あるよ。\n\n");
        items.stream().limit(15).forEach(item -> out.append("・").append(item.text()).append("\n"));
        if (items.size() > 15) out.append("ほか").append(items.size() - 15).append("件");
        return out.toString().stripTrailing();
    }

    private String fullBriefing(String userId) {
        StringBuilder out = new StringBuilder(todayBriefing(userId));
        List<BenlyStore.Item> shopping = store.listShoppingItems(userId);
        out.append("\n\n買い物リスト：").append(shopping.size()).append("件");
        shopping.stream().limit(5).forEach(item -> out.append("\n・").append(item.text()));
        try {
            MorningBriefing briefing = morningBriefing(userId);
            out.append("\n\n今日の持ち物チェック");
            if (briefing.belongings().isEmpty()) out.append("\n特に追加の持ち物は見つからなかったよ。");
            else briefing.belongings().forEach(item -> out.append("\n・").append(item));
            out.append("\n\n").append(briefing.comment());
        } catch (RuntimeException e) {
            try { out.append("\n\n").append(weather.handle(userId, "今日傘いる？")); }
            catch (RuntimeException ignored) { out.append("\n\n天気情報は一時的に取得できなかったよ。"); }
        }
        return out.toString();
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    public record MorningBriefing(String area, List<BenlyStore.ScheduleItem> schedules,
                                  List<BenlyStore.Item> tasks, List<BenlyStore.Item> shopping,
                                  WeatherService.Forecast forecast, List<String> belongings,
                                  String comment) {}
    private record Schedule(String title, LocalDateTime at) {}
}
