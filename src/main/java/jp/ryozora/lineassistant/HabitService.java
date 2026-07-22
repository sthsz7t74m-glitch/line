package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HabitService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Pattern TIME = Pattern.compile("^(?:([01]?\\d|2[0-3])):([0-5]\\d)$");
    private static final Pattern DAY_TOKEN = Pattern.compile("^[月火水木金土日]+(?:曜(?:日)?)?$");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final String ALL_DAYS = "1234567";

    private final JdbcTemplate jdbc;
    private final BenlyStore store;

    public HabitService(JdbcTemplate jdbc, BenlyStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        return text.equals("習慣") || text.equals("習慣一覧") || text.equals("今日の習慣")
                || text.equals("習慣統計") || text.equals("習慣の記録")
                || text.startsWith("習慣 ") || text.startsWith("習慣達成 ")
                || text.startsWith("習慣達成ID ") || text.startsWith("習慣取消 ")
                || text.startsWith("習慣削除 ") || text.startsWith("習慣編集 ")
                || text.startsWith("習慣休止 ") || text.startsWith("習慣再開 ");
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        store.ensureUser(userId);

        if (text.startsWith("習慣達成ID ")) return completeById(userId, parseLong(text.substring(7)), true);
        if (text.startsWith("習慣達成 ")) return completeByNumber(userId, text.substring(5));
        if (text.startsWith("習慣取消 ")) return undoByNumber(userId, text.substring(5));
        if (text.startsWith("習慣削除 ")) return delete(userId, text.substring(5));
        if (text.startsWith("習慣編集 ")) return edit(userId, text.substring(5));
        if (text.startsWith("習慣休止 ")) return setActive(userId, text.substring(5), false);
        if (text.startsWith("習慣再開 ")) return setActive(userId, text.substring(5), true);
        if (text.equals("習慣一覧")) return listAll(userId);
        if (text.equals("習慣統計") || text.equals("習慣の記録")) return statistics(userId);
        if (text.equals("習慣") || text.equals("今日の習慣")) return today(userId);
        if (text.startsWith("習慣 ")) return add(userId, text.substring(3));
        return null;
    }

    @Transactional
    String add(String userId, String raw) {
        ParsedHabit parsed = parseHabit(raw);
        if (parsed == null) {
            return "習慣を読み取れなかったよ。例：習慣 筋トレ / 習慣 薬 毎日 21:00";
        }
        Long id = jdbc.queryForObject("""
                insert into habits(line_user_id,name,active_days,reminder_time)
                values (?,?,?,?) returning id
                """, Long.class, userId, parsed.name(), parsed.days(),
                parsed.reminder() == null ? null : Time.valueOf(parsed.reminder()));
        return "習慣を追加したよ。\n"
                + "内容　" + parsed.name() + "\n"
                + "曜日　" + daysLabel(parsed.days()) + "\n"
                + "通知　" + (parsed.reminder() == null ? "なし" : parsed.reminder().format(DateTimeFormatter.ofPattern("H:mm")))
                + "\n\n今日の分は『今日の習慣』から達成できるよ。";
    }

    String today(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        List<Habit> habits = habitsForDate(userId, today);
        if (habits.isEmpty()) return "今日やる習慣はまだないよ。『習慣 筋トレ』のように追加できるよ。";

        Set<Long> completed = completedIds(userId, today);
        StringBuilder out = new StringBuilder("今日の習慣\n━━━━━━━━━━\n");
        for (int i = 0; i < habits.size(); i++) {
            Habit habit = habits.get(i);
            boolean done = completed.contains(habit.id());
            out.append(done ? "■ " : "□ ").append(i + 1).append(".　").append(habit.name());
            if (habit.reminder() != null) {
                out.append("　").append(habit.reminder().format(DateTimeFormatter.ofPattern("H:mm")));
            }
            int streak = streak(habit, today);
            if (streak > 0) out.append("　連続").append(streak).append("日");
            out.append("\n");
        }
        out.append("\n達成：習慣達成 1\n取消：習慣取消 1");
        return out.toString().stripTrailing();
    }

    String listAll(String userId) {
        List<Habit> habits = allHabits(userId);
        if (habits.isEmpty()) return "登録中の習慣はまだないよ。";
        StringBuilder out = new StringBuilder("習慣一覧\n━━━━━━━━━━\n");
        for (int i = 0; i < habits.size(); i++) {
            Habit habit = habits.get(i);
            out.append(i + 1).append(".　").append(habit.active() ? "［有効］" : "［休止］")
                    .append(habit.name()).append("\n　")
                    .append(daysLabel(habit.days())).append(" / ")
                    .append(habit.reminder() == null ? "通知なし" : habit.reminder().format(DateTimeFormatter.ofPattern("H:mm")))
                    .append("\n");
        }
        out.append("\n編集：習慣編集 1 読書 平日 22:00\n休止：習慣休止 1\n削除：習慣削除 1");
        return out.toString().stripTrailing();
    }

    @Transactional
    String completeByNumber(String userId, String value) {
        Integer number = parseNumber(value);
        if (number == null || number < 1) return "例：習慣達成 1 のように送ってね。";
        List<Habit> today = habitsForDate(userId, LocalDate.now(TOKYO));
        if (number > today.size()) return "その番号の習慣は今日の一覧にないよ。『今日の習慣』で確認してね。";
        return completeById(userId, today.get(number - 1).id(), false);
    }

    @Transactional
    String completeById(String userId, Long habitId, boolean fromButton) {
        if (habitId == null) return "習慣を特定できなかったよ。";
        Habit habit = habitById(userId, habitId);
        LocalDate today = LocalDate.now(TOKYO);
        if (habit == null || !habit.active() || !scheduledOn(habit.days(), today)) {
            return "今日の対象になっている習慣が見つからなかったよ。";
        }
        int inserted = jdbc.update("""
                insert into habit_completions(habit_id,line_user_id,completed_on)
                values (?,?,?) on conflict(habit_id,completed_on) do nothing
                """, habit.id(), userId, today);
        if (inserted == 0) return "「" + habit.name() + "」は今日すでに達成済みだよ。";

        jdbc.update("update benly_users set experience=experience+5,updated_at=current_timestamp where line_user_id=?", userId);
        jdbc.update("insert into experience_logs(line_user_id,points,reason) values (?,5,'HABIT_COMPLETED')", userId);
        int streak = streak(habit, today);
        return "「" + habit.name() + "」を達成！\n経験値 +5\n現在 " + streak + "日連続。";
    }

    @Transactional
    String undoByNumber(String userId, String value) {
        Integer number = parseNumber(value);
        if (number == null || number < 1) return "例：習慣取消 1 のように送ってね。";
        List<Habit> today = habitsForDate(userId, LocalDate.now(TOKYO));
        if (number > today.size()) return "その番号の習慣は今日の一覧にないよ。";
        Habit habit = today.get(number - 1);
        int deleted = jdbc.update("delete from habit_completions where habit_id=? and line_user_id=? and completed_on=?",
                habit.id(), userId, LocalDate.now(TOKYO));
        if (deleted == 0) return "「" + habit.name() + "」はまだ達成済みになっていないよ。";
        jdbc.update("update benly_users set experience=greatest(0,experience-5),updated_at=current_timestamp where line_user_id=?", userId);
        jdbc.update("insert into experience_logs(line_user_id,points,reason) values (?,-5,'HABIT_COMPLETION_CANCELLED')", userId);
        return "「" + habit.name() + "」の今日の達成を取り消したよ。";
    }

    @Transactional
    String delete(String userId, String value) {
        Integer number = parseNumber(value);
        List<Habit> habits = allHabits(userId);
        if (number == null || number < 1 || number > habits.size()) {
            return "例：習慣削除 2 のように送ってね。番号は『習慣一覧』で確認できるよ。";
        }
        Habit habit = habits.get(number - 1);
        jdbc.update("delete from habits where id=? and line_user_id=?", habit.id(), userId);
        return "習慣「" + habit.name() + "」を削除したよ。";
    }

    @Transactional
    String edit(String userId, String value) {
        int space = value.indexOf(' ');
        if (space < 1) return "例：習慣編集 2 読書 月水金 22:00 のように送ってね。";
        Integer number = parseNumber(value.substring(0, space));
        List<Habit> habits = allHabits(userId);
        if (number == null || number < 1 || number > habits.size()) return "その番号の習慣は見つからなかったよ。";
        ParsedHabit parsed = parseHabit(value.substring(space + 1));
        if (parsed == null) return "変更内容を読み取れなかったよ。";
        Habit habit = habits.get(number - 1);
        jdbc.update("""
                update habits set name=?,active_days=?,reminder_time=?,updated_at=current_timestamp
                where id=? and line_user_id=?
                """, parsed.name(), parsed.days(), parsed.reminder() == null ? null : Time.valueOf(parsed.reminder()),
                habit.id(), userId);
        return "習慣を変更したよ。\n" + parsed.name() + " / " + daysLabel(parsed.days()) + " / "
                + (parsed.reminder() == null ? "通知なし" : parsed.reminder().format(DateTimeFormatter.ofPattern("H:mm")));
    }

    @Transactional
    String setActive(String userId, String value, boolean active) {
        Integer number = parseNumber(value);
        List<Habit> habits = allHabits(userId);
        if (number == null || number < 1 || number > habits.size()) return "番号は『習慣一覧』で確認してね。";
        Habit habit = habits.get(number - 1);
        jdbc.update("update habits set active=?,updated_at=current_timestamp where id=? and line_user_id=?",
                active, habit.id(), userId);
        return "習慣「" + habit.name() + "」を" + (active ? "再開" : "休止") + "したよ。";
    }

    String statistics(String userId) {
        List<Habit> habits = allHabits(userId).stream().filter(Habit::active).toList();
        if (habits.isEmpty()) return "集計できる有効な習慣はまだないよ。";
        LocalDate today = LocalDate.now(TOKYO);
        LocalDate start = today.minusDays(29);
        StringBuilder out = new StringBuilder("習慣の30日記録\n━━━━━━━━━━\n");
        for (Habit habit : habits) {
            int opportunities = 0;
            for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
                if (scheduledOn(habit.days(), date)) opportunities++;
            }
            Integer done = jdbc.queryForObject("""
                    select count(*) from habit_completions
                    where habit_id=? and line_user_id=? and completed_on>=? and completed_on<=?
                    """, Integer.class, habit.id(), userId, start, today);
            int completed = done == null ? 0 : done;
            int rate = opportunities == 0 ? 0 : (int) Math.round(completed * 100.0 / opportunities);
            out.append(habit.name()).append("\n　達成率 ").append(rate).append("%（")
                    .append(completed).append("/").append(opportunities).append("）")
                    .append(" / 連続").append(streak(habit, today)).append("日\n");
        }
        return out.toString().stripTrailing();
    }

    public List<HabitReminder> dueReminders(LocalDateTime now) {
        LocalTime minute = now.toLocalTime().withSecond(0).withNano(0);
        String day = String.valueOf(now.getDayOfWeek().getValue());
        return jdbc.query("""
                select h.id,h.line_user_id,h.name
                from habits h
                where h.active=true and h.reminder_time=? and strpos(h.active_days,?)>0
                  and not exists (
                    select 1 from habit_completions c
                    where c.habit_id=h.id and c.completed_on=?
                  )
                order by h.id
                """, (rs, i) -> new HabitReminder(rs.getLong("id"), rs.getString("line_user_id"), rs.getString("name")),
                Time.valueOf(minute), day, now.toLocalDate());
    }

    public int countForUser(String userId) {
        Integer value = jdbc.queryForObject("select count(*) from habits where line_user_id=?", Integer.class, userId);
        return value == null ? 0 : value;
    }

    private ParsedHabit parseHabit(String raw) {
        String text = normalize(raw);
        if (text.isBlank()) return null;
        List<String> tokens = new ArrayList<>(Arrays.asList(text.split(" ")));
        LocalTime reminder = null;
        String days = ALL_DAYS;

        for (Iterator<String> iterator = tokens.iterator(); iterator.hasNext();) {
            String token = iterator.next();
            Matcher timeMatcher = TIME.matcher(token);
            if (timeMatcher.matches()) {
                reminder = LocalTime.of(Integer.parseInt(timeMatcher.group(1)), Integer.parseInt(timeMatcher.group(2)));
                iterator.remove();
                continue;
            }
            String parsedDays = parseDaysToken(token);
            if (parsedDays != null) {
                days = parsedDays;
                iterator.remove();
            }
        }
        String name = String.join(" ", tokens).strip();
        if (name.isBlank()) return null;
        return new ParsedHabit(name, days, reminder);
    }

    private String parseDaysToken(String token) {
        if (token.equals("毎日")) return ALL_DAYS;
        if (token.equals("平日")) return "12345";
        if (token.equals("土日")) return "67";
        if (!DAY_TOKEN.matcher(token).matches()) return null;
        String cleaned = token.replace("曜日", "").replace("曜", "");
        StringBuilder days = new StringBuilder();
        String names = "月火水木金土日";
        for (int i = 0; i < names.length(); i++) {
            if (cleaned.indexOf(names.charAt(i)) >= 0) days.append(i + 1);
        }
        return days.isEmpty() ? null : days.toString();
    }

    private List<Habit> allHabits(String userId) {
        return jdbc.query("""
                select id,name,active_days,reminder_time,active
                from habits where line_user_id=? order by active desc,id
                """, (rs, i) -> new Habit(rs.getLong("id"), rs.getString("name"), rs.getString("active_days"),
                rs.getTime("reminder_time") == null ? null : rs.getTime("reminder_time").toLocalTime(),
                rs.getBoolean("active")), userId);
    }

    private List<Habit> habitsForDate(String userId, LocalDate date) {
        String day = String.valueOf(date.getDayOfWeek().getValue());
        return jdbc.query("""
                select id,name,active_days,reminder_time,active
                from habits where line_user_id=? and active=true and strpos(active_days,?)>0
                order by reminder_time nulls last,id
                """, (rs, i) -> new Habit(rs.getLong("id"), rs.getString("name"), rs.getString("active_days"),
                rs.getTime("reminder_time") == null ? null : rs.getTime("reminder_time").toLocalTime(), true),
                userId, day);
    }

    private Habit habitById(String userId, long habitId) {
        return jdbc.query("""
                select id,name,active_days,reminder_time,active from habits
                where id=? and line_user_id=?
                """, rs -> rs.next() ? new Habit(rs.getLong("id"), rs.getString("name"), rs.getString("active_days"),
                rs.getTime("reminder_time") == null ? null : rs.getTime("reminder_time").toLocalTime(),
                rs.getBoolean("active")) : null, habitId, userId);
    }

    private Set<Long> completedIds(String userId, LocalDate date) {
        return new HashSet<>(jdbc.query("""
                select habit_id from habit_completions where line_user_id=? and completed_on=?
                """, (rs, i) -> rs.getLong(1), userId, date));
    }

    private int streak(Habit habit, LocalDate today) {
        LocalDate start = today.minusDays(400);
        Set<LocalDate> completed = new HashSet<>(jdbc.query("""
                select completed_on from habit_completions
                where habit_id=? and completed_on>=? and completed_on<=?
                """, (rs, i) -> rs.getDate(1).toLocalDate(), habit.id(), start, today));

        LocalDate cursor = today;
        if (scheduledOn(habit.days(), cursor) && !completed.contains(cursor)) cursor = cursor.minusDays(1);
        int count = 0;
        int checked = 0;
        while (!cursor.isBefore(start) && checked < 400) {
            if (scheduledOn(habit.days(), cursor)) {
                checked++;
                if (!completed.contains(cursor)) break;
                count++;
            }
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    private boolean scheduledOn(String days, LocalDate date) {
        return days != null && days.indexOf(Character.forDigit(date.getDayOfWeek().getValue(), 10)) >= 0;
    }

    private String daysLabel(String days) {
        if (ALL_DAYS.equals(days)) return "毎日";
        if ("12345".equals(days)) return "平日";
        if ("67".equals(days)) return "土日";
        String[] names = {"月", "火", "水", "木", "金", "土", "日"};
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= 7; i++) if (days.contains(String.valueOf(i))) labels.add(names[i - 1]);
        return String.join("・", labels) + "曜";
    }

    private Integer parseNumber(String value) {
        Matcher matcher = NUMBER.matcher(value == null ? "" : value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Long parseLong(String value) {
        try { return Long.parseLong(value.strip()); } catch (RuntimeException e) { return null; }
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record ParsedHabit(String name, String days, LocalTime reminder) {}
    private record Habit(long id, String name, String days, LocalTime reminder, boolean active) {}
    public record HabitReminder(long id, String userId, String name) {}
}
