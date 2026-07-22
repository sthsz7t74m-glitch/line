package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

@Service
public class DailyProgressService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final JdbcTemplate jdbc;
    private final BenlyStore store;

    public DailyProgressService(JdbcTemplate jdbc, BenlyStore store) {
        this.jdbc = jdbc;
        this.store = store;
        jdbc.execute("""
                create table if not exists daily_mission_rewards (
                    line_user_id varchar(255) not null,
                    mission_date date not null,
                    rewarded_at timestamp with time zone not null default current_timestamp,
                    primary key(line_user_id, mission_date)
                )
                """);
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        return text.equals("今日のミッション") || text.equals("ミッション") || text.equals("デイリー")
                || text.equals("統計") || text.equals("成績") || text.equals("利用状況")
                || text.equals("今月") || text.equals("今月の統計") || text.equals("月間統計")
                || text.equals("今週ランキング") || text.equals("今週の成績")
                || text.equals("カレンダー") || text.equals("週間カレンダー") || text.equals("今週のカレンダー");
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        store.ensureUser(userId);
        if (text.contains("ミッション") || text.equals("デイリー")) return missions(userId);
        if (text.contains("カレンダー")) return weeklyCalendar(userId);
        if (text.contains("今週")) return weeklyScore(userId);
        if (text.contains("今月") || text.contains("月間")) return monthlyStats(userId);
        return allStats(userId);
    }

    @Transactional
    public String missions(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        Instant start = today.atStartOfDay(TOKYO).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(TOKYO).toInstant();
        int completedTasks = count("select count(*) from tasks where line_user_id=? and completed_at>=? and completed_at<?", userId, start, end);
        int purchased = count("select count(*) from shopping_items where line_user_id=? and purchased_at>=? and purchased_at<?", userId, start, end);
        int memos = count("select count(*) from memos where line_user_id=? and created_at>=? and created_at<?", userId, start, end);
        boolean all = completedTasks > 0 && purchased > 0 && memos > 0;
        boolean rewarded = countDate("select count(*) from daily_mission_rewards where line_user_id=? and mission_date=?", userId, today) > 0;

        if (all && !rewarded) {
            int inserted = jdbc.update("insert into daily_mission_rewards(line_user_id,mission_date) values (?,?) on conflict do nothing", userId, today);
            if (inserted == 1) {
                jdbc.update("update benly_users set experience=experience+50, updated_at=current_timestamp where line_user_id=?", userId);
                jdbc.update("insert into experience_logs(line_user_id,points,reason) values (?,50,'DAILY_MISSION_COMPLETE')", userId);
                rewarded = true;
            }
        }

        return """
                今日のミッション
                ━━━━━━━━━━
                %s タスクを1件完了
                %s 買い物を1件購入
                %s メモを1件追加

                %s
                """.formatted(mark(completedTasks > 0), mark(purchased > 0), mark(memos > 0),
                all ? (rewarded ? "全達成！ EXP +50 を受け取ったよ。" : "全達成！")
                        : "全部達成すると EXP +50！").strip();
    }

    public String allStats(String userId) {
        LocalDate first = firstUseDate(userId);
        int exp = store.experience(userId);
        return """
                ベンリー統計
                ━━━━━━━━━━
                メモ　%d件
                タスク完了　%d件
                買い物完了　%d件
                予定　%d件
                累計経験値　%d
                連続利用　%d日

                利用開始　%s
                利用日数　%d日
                """.formatted(
                countSimple("select count(*) from memos where line_user_id=? and archived=false", userId),
                countSimple("select count(*) from tasks where line_user_id=? and completed=true", userId),
                countSimple("select count(*) from shopping_items where line_user_id=? and purchased=true", userId),
                countSimple("select count(*) from schedules where line_user_id=?", userId),
                exp, streak(userId), first.format(DateTimeFormatter.ofPattern("yyyy/M/d")),
                Duration.between(first.atStartOfDay(), LocalDate.now(TOKYO).plusDays(1).atStartOfDay()).toDays()).strip();
    }

    public String monthlyStats(String userId) {
        YearMonth month = YearMonth.now(TOKYO);
        Instant start = month.atDay(1).atStartOfDay(TOKYO).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(TOKYO).toInstant();
        return """
                %d年%d月の統計
                ━━━━━━━━━━
                予定　%d件
                完了タスク　%d件
                買い物完了　%d件
                メモ追加　%d件
                獲得経験値　+%d
                """.formatted(month.getYear(), month.getMonthValue(),
                count("select count(*) from schedules where line_user_id=? and starts_at>=? and starts_at<?", userId, start, end),
                count("select count(*) from tasks where line_user_id=? and completed_at>=? and completed_at<?", userId, start, end),
                count("select count(*) from shopping_items where line_user_id=? and purchased_at>=? and purchased_at<?", userId, start, end),
                count("select count(*) from memos where line_user_id=? and created_at>=? and created_at<?", userId, start, end),
                sum("select coalesce(sum(points),0) from experience_logs where line_user_id=? and created_at>=? and created_at<?", userId, start, end)).strip();
    }

    public String weeklyScore(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        Instant start = monday.atStartOfDay(TOKYO).toInstant();
        Instant end = monday.plusDays(7).atStartOfDay(TOKYO).toInstant();
        int completed = count("select count(*) from tasks where line_user_id=? and completed_at>=? and completed_at<?", userId, start, end);
        int createdTasks = count("select count(*) from tasks where line_user_id=? and created_at<?", userId, start, end);
        int purchased = count("select count(*) from shopping_items where line_user_id=? and purchased_at>=? and purchased_at<?", userId, start, end);
        int schedules = count("select count(*) from schedules where line_user_id=? and starts_at>=? and starts_at<?", userId, start, end);
        int score = Math.min(5, Math.max(1, (completed + purchased + schedules) / 3 + 1));
        return """
                今週の成績
                ━━━━━━━━━━
                %s

                タスク完了　%d件
                買い物完了　%d件
                予定　%d件
                活動スコア　%d点

                %s
                """.formatted("★".repeat(score) + "☆".repeat(5 - score), completed, purchased, schedules,
                completed * 10 + purchased * 5 + schedules * 2,
                createdTasks > completed ? "未完了タスクも少しずつ片づけていこう。" : "いいペース！この調子でいこう。 ").strip();
    }

    public String weeklyCalendar(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        Instant start = today.atStartOfDay(TOKYO).toInstant();
        Instant end = today.plusDays(7).atStartOfDay(TOKYO).toInstant();
        List<Event> events = jdbc.query("""
                select title, starts_at from schedules
                where line_user_id=? and starts_at>=? and starts_at<?
                order by starts_at
                """, (rs, i) -> new Event(rs.getString("title"), rs.getTimestamp("starts_at").toInstant().atZone(TOKYO).toLocalDateTime()),
                userId, Timestamp.from(start), Timestamp.from(end));
        StringBuilder out = new StringBuilder("週間カレンダー\n━━━━━━━━━━\n");
        for (int i = 0; i < 7; i++) {
            LocalDate date = today.plusDays(i);
            out.append("\n").append(date.format(DateTimeFormatter.ofPattern("M/d"))).append("（")
                    .append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE)).append("）\n");
            List<Event> day = events.stream().filter(e -> e.at().toLocalDate().equals(date)).toList();
            if (day.isEmpty()) out.append("・予定なし\n");
            else day.forEach(e -> out.append("・").append(e.at().format(DateTimeFormatter.ofPattern("H:mm")))
                    .append("　").append(e.title()).append("\n"));
        }
        return out.toString().stripTrailing();
    }

    private String mark(boolean done) { return done ? "■" : "□"; }

    private int count(String sql, String userId, Instant start, Instant end) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId, Timestamp.from(start), Timestamp.from(end));
        return value == null ? 0 : value;
    }

    private int sum(String sql, String userId, Instant start, Instant end) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId, Timestamp.from(start), Timestamp.from(end));
        return value == null ? 0 : value;
    }

    private int countSimple(String sql, String userId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId);
        return value == null ? 0 : value;
    }

    private int countDate(String sql, String userId, LocalDate date) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId, date);
        return value == null ? 0 : value;
    }

    private LocalDate firstUseDate(String userId) {
        LocalDate date = jdbc.query("select created_at from benly_users where line_user_id=?", rs ->
                rs.next() ? rs.getTimestamp(1).toInstant().atZone(TOKYO).toLocalDate() : null, userId);
        return date == null ? LocalDate.now(TOKYO) : date;
    }

    private int streak(String userId) {
        List<LocalDate> dates = jdbc.query("""
                select distinct activity_date from (
                    select created_at::date activity_date from memos where line_user_id=?
                    union select completed_at::date from tasks where line_user_id=? and completed_at is not null
                    union select purchased_at::date from shopping_items where line_user_id=? and purchased_at is not null
                    union select starts_at::date from schedules where line_user_id=?
                ) x order by activity_date desc limit 365
                """, (rs, i) -> rs.getDate(1).toLocalDate(), userId, userId, userId, userId);
        LocalDate cursor = LocalDate.now(TOKYO);
        int streak = 0;
        if (!dates.contains(cursor)) cursor = cursor.minusDays(1);
        while (dates.contains(cursor)) { streak++; cursor = cursor.minusDays(1); }
        return streak;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record Event(String title, LocalDateTime at) {}
}
