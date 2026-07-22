package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdvancedScheduleService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern INTERNAL = Pattern.compile("^予定\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+(.+)$");
    private static final Pattern TIME = Pattern.compile("(?:(午前|午後)\\s*)?(\\d{1,2})(?:時|じ|:)(\\d{1,2})?(?:分|ぷん|ふん)?(?:半|はん)?");
    private static final Pattern REMINDER = Pattern.compile("(\\d{1,3})分前");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private final JdbcTemplate jdbc;

    public AdvancedScheduleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        return text.startsWith("予定 ") || text.equals("予定一覧") || text.equals("今後の予定")
                || text.startsWith("予定削除 ") || text.startsWith("予定変更 ")
                || text.startsWith("通知変更 ") || text.startsWith("繰り返し変更 ")
                || text.startsWith("毎日") || text.startsWith("毎週") || text.startsWith("毎月")
                || text.startsWith("平日") || text.startsWith("土日");
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        if (text.equals("予定一覧") || text.equals("今後の予定")) return listUpcoming(userId);
        if (text.startsWith("予定削除 ")) return delete(userId, text.substring(5).strip());
        if (text.startsWith("予定変更 ")) return change(userId, text.substring(5).strip());
        if (text.startsWith("通知変更 ")) return changeReminders(userId, text.substring(5).strip());
        if (text.startsWith("繰り返し変更 ")) return "繰り返しの変更は、いったん『予定削除 番号』で消して登録し直してね。シリーズ全体を削除できるよ。";
        if (isRecurring(text)) return addRecurring(userId, text);
        Matcher internal = INTERNAL.matcher(text);
        if (internal.matches()) return addSingle(userId, internal.group(1), internal.group(2));
        return null;
    }

    private boolean isRecurring(String text) {
        return text.startsWith("毎日") || text.startsWith("毎週") || text.startsWith("毎月")
                || text.startsWith("平日") || text.startsWith("土日");
    }

    @Transactional
    String addSingle(String userId, String dateTime, String rawTitle) {
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(dateTime, INPUT);
        } catch (RuntimeException e) {
            return "日時を読み取れなかったよ。例：明日19時 歯医者";
        }
        ReminderTitle parsed = parseReminderTitle(rawTitle);
        jdbc.update("insert into schedules(line_user_id,title,starts_at,reminder_minutes) values (?,?,?,?)",
                userId, parsed.title(), Timestamp.from(local.atZone(TOKYO).toInstant()), parsed.reminders());
        return addedMessage(local, parsed.title(), null, parsed.reminders());
    }

    @Transactional
    String addRecurring(String userId, String text) {
        Recurrence recurrence = parseRecurrence(text);
        if (recurrence == null) return "繰り返し予定を読み取れなかったよ。例：毎週月曜19時 ジム";
        String seriesId = UUID.randomUUID().toString();
        LocalDate end = LocalDate.now(TOKYO).plusMonths(12);
        int count = 0;
        LocalDate cursor = recurrence.firstDate();
        while (!cursor.isAfter(end) && count < 400) {
            if (recurrence.matches(cursor)) {
                LocalDateTime at = LocalDateTime.of(cursor, recurrence.time());
                jdbc.update("insert into schedules(line_user_id,title,starts_at,series_id,recurrence_label,reminder_minutes) values (?,?,?,?,?,?)",
                        userId, recurrence.title(), Timestamp.from(at.atZone(TOKYO).toInstant()), seriesId,
                        recurrence.label(), recurrence.reminders());
                count++;
            }
            cursor = cursor.plusDays(1);
        }
        return addedMessage(LocalDateTime.of(recurrence.firstDate(), recurrence.time()), recurrence.title(), recurrence.label(), recurrence.reminders())
                + "\n\n先1年分を登録したよ（" + count + "件）。";
    }

    private Recurrence parseRecurrence(String text) {
        Matcher tm = TIME.matcher(text);
        if (!tm.find()) return null;
        LocalTime time = parseTime(tm);
        String prefix = text.substring(0, tm.start()).strip();
        ReminderTitle rt = parseReminderTitle(text.substring(tm.end()).strip());
        if (rt.title().isBlank()) return null;
        LocalDate today = LocalDate.now(TOKYO);

        if (prefix.startsWith("毎日")) {
            return new Recurrence(today, time, rt.title(), "毎日", rt.reminders(), d -> true);
        }
        if (prefix.startsWith("平日")) {
            return new Recurrence(today, time, rt.title(), "平日", rt.reminders(), d -> d.getDayOfWeek().getValue() <= 5);
        }
        if (prefix.startsWith("土日")) {
            return new Recurrence(today, time, rt.title(), "土日", rt.reminders(), d -> d.getDayOfWeek().getValue() >= 6);
        }
        if (prefix.startsWith("毎週")) {
            DayOfWeek dow = parseDayOfWeek(prefix);
            if (dow == null) return null;
            LocalDate first = today.getDayOfWeek() == dow ? today : today.with(TemporalAdjusters.next(dow));
            String label = "毎週" + dow.getDisplayName(TextStyle.SHORT, Locale.JAPANESE) + "曜";
            return new Recurrence(first, time, rt.title(), label, rt.reminders(), d -> d.getDayOfWeek() == dow);
        }
        if (prefix.startsWith("毎月")) {
            Matcher n = NUMBER.matcher(prefix);
            if (!n.find()) return null;
            int day = Integer.parseInt(n.group(1));
            if (day < 1 || day > 31) return null;
            LocalDate first = safeDate(today.getYear(), today.getMonthValue(), day);
            if (first == null || first.isBefore(today)) {
                YearMonth next = YearMonth.from(today).plusMonths(1);
                first = safeDate(next.getYear(), next.getMonthValue(), day);
            }
            if (first == null) return null;
            String label = "毎月" + day + "日";
            return new Recurrence(first, time, rt.title(), label, rt.reminders(), d -> d.getDayOfMonth() == day);
        }
        return null;
    }

    private String listUpcoming(String userId) {
        List<Row> rows = jdbc.query("""
                select id,title,starts_at,series_id,recurrence_label,reminder_minutes
                from schedules where line_user_id=? and starts_at>=current_timestamp
                order by starts_at limit 30
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atOffset(OFFSET), rs.getString("series_id"),
                rs.getString("recurrence_label"), rs.getString("reminder_minutes")), userId);
        if (rows.isEmpty()) return "今後の予定はまだないよ。";
        StringBuilder out = new StringBuilder("CALENDAR  今後の予定\n");
        LocalDate previous = null;
        int number = 1;
        for (Row row : rows) {
            LocalDate date = row.at().toLocalDate();
            if (!date.equals(previous)) {
                out.append("\n").append(relative(date)).append("  ")
                        .append(date.format(DateTimeFormatter.ofPattern("M/d")))
                        .append("(").append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE)).append(")\n");
                previous = date;
            }
            out.append("No.").append(number++).append("  ")
                    .append(row.at().format(DateTimeFormatter.ofPattern("HH:mm"))).append("  ")
                    .append(row.title());
            if (row.recurrence() != null) out.append("  [").append(row.recurrence()).append("]");
            out.append("\n");
        }
        return out.toString().stripTrailing();
    }

    @Transactional
    String delete(String userId, String value) {
        Integer number = parseNumber(value);
        if (number == null) return "例：予定削除 2 のように送ってね。";
        Row row = rowByDisplayNumber(userId, number);
        if (row == null) return "その番号の予定は見つからなかったよ。『予定一覧』で確認してね。";
        int changed;
        if (row.seriesId() != null && !row.seriesId().isBlank()) {
            changed = jdbc.update("delete from schedules where line_user_id=? and series_id=? and starts_at>=?",
                    userId, row.seriesId(), Timestamp.from(row.at().toInstant()));
            return "繰り返し予定「" + row.title() + "」を、この回以降 " + changed + "件削除したよ。";
        }
        changed = jdbc.update("delete from schedules where line_user_id=? and id=?", userId, row.id());
        return changed == 1 ? "予定「" + row.title() + "」を削除したよ。" : "予定を削除できなかったよ。";
    }

    @Transactional
    String change(String userId, String value) {
        int space = value.indexOf(' ');
        if (space < 1) return "例：予定変更 2 2026-07-30 20:00 会議";
        Integer number = parseNumber(value.substring(0, space));
        if (number == null) return "予定番号を読み取れなかったよ。";
        Row row = rowByDisplayNumber(userId, number);
        if (row == null) return "その番号の予定は見つからなかったよ。";
        String rest = value.substring(space + 1).strip();
        Matcher m = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+(.+)$").matcher(rest);
        if (!m.matches()) return "変更は『予定変更 2 2026-07-30 20:00 会議』の形で送ってね。";
        LocalDateTime local = LocalDateTime.parse(m.group(1), INPUT);
        jdbc.update("update schedules set starts_at=?,title=? where line_user_id=? and id=?",
                Timestamp.from(local.atZone(TOKYO).toInstant()), m.group(2), userId, row.id());
        return "予定を変更したよ。\n" + addedMessage(local, m.group(2), row.recurrence(), row.reminders());
    }

    @Transactional
    String changeReminders(String userId, String value) {
        int space = value.indexOf(' ');
        if (space < 1) return "例：通知変更 2 30分前と10分前";
        Integer number = parseNumber(value.substring(0, space));
        Row row = number == null ? null : rowByDisplayNumber(userId, number);
        if (row == null) return "その番号の予定は見つからなかったよ。";
        String reminders = parseReminders(value.substring(space + 1));
        if (row.seriesId() != null && !row.seriesId().isBlank()) {
            jdbc.update("update schedules set reminder_minutes=? where line_user_id=? and series_id=? and starts_at>=?",
                    reminders, userId, row.seriesId(), Timestamp.from(row.at().toInstant()));
        } else {
            jdbc.update("update schedules set reminder_minutes=? where line_user_id=? and id=?", reminders, userId, row.id());
        }
        return "通知タイミングを変更したよ。\nREMIND  " + reminderLabel(reminders);
    }

    private Row rowByDisplayNumber(String userId, int number) {
        if (number < 1) return null;
        List<Row> rows = jdbc.query("""
                select id,title,starts_at,series_id,recurrence_label,reminder_minutes
                from schedules where line_user_id=? and starts_at>=current_timestamp
                order by starts_at limit 30
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atOffset(OFFSET), rs.getString("series_id"),
                rs.getString("recurrence_label"), rs.getString("reminder_minutes")), userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private ReminderTitle parseReminderTitle(String raw) {
        String reminders = parseReminders(raw);
        String title = raw.replaceAll("\\d{1,3}分前", "")
                .replaceAll("通知\\d+回", "")
                .replace("と", " ").replaceAll("\\s+", " ").strip();
        return new ReminderTitle(title, reminders);
    }

    private String parseReminders(String raw) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        Matcher matcher = REMINDER.matcher(raw);
        while (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
        Matcher count = Pattern.compile("通知(\\d+)回").matcher(raw);
        if (values.isEmpty() && count.find()) {
            int n = Math.max(1, Math.min(5, Integer.parseInt(count.group(1))));
            int[] defaults = {30, 10, 5, 1, 0};
            for (int i = 0; i < n; i++) values.add(defaults[i]);
        }
        if (values.isEmpty()) values.add(30);
        return values.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("30");
    }

    private String addedMessage(LocalDateTime local, String title, String recurrence, String reminders) {
        String dayPart = local.getHour() < 11 ? "MORNING" : local.getHour() < 15 ? "DAY" : local.getHour() < 18 ? "EVENING" : "NIGHT";
        return "━━━━━━━━━━━━\nSCHEDULE  予定を追加したよ！\n━━━━━━━━━━━━\n\n"
                + "DATE  " + local.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "("
                + local.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE) + ")\n"
                + "      " + relative(local.toLocalDate()) + "\n\n"
                + "TIME  " + dayPart + "  " + local.format(DateTimeFormatter.ofPattern("HH:mm")) + "\n"
                + "TITLE " + title + "\n"
                + (recurrence == null ? "" : "LOOP  " + recurrence + "\n")
                + "REMIND " + reminderLabel(reminders) + "\n━━━━━━━━━━━━";
    }

    private String reminderLabel(String reminders) {
        return Arrays.stream(reminders.split(",")).map(v -> v.equals("0") ? "開始時" : v + "分前")
                .reduce((a, b) -> a + " / " + b).orElse("30分前");
    }

    private String relative(LocalDate date) {
        long days = Duration.between(LocalDate.now(TOKYO).atStartOfDay(), date.atStartOfDay()).toDays();
        if (days == 0) return "今日";
        if (days == 1) return "明日";
        if (days == 2) return "あさって";
        return days > 0 ? days + "日後" : Math.abs(days) + "日前";
    }

    private LocalTime parseTime(Matcher m) {
        int hour = Integer.parseInt(m.group(2));
        int minute = m.group().endsWith("半") || m.group().endsWith("はん") ? 30
                : m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if ("午後".equals(m.group(1)) && hour < 12) hour += 12;
        if ("午前".equals(m.group(1)) && hour == 12) hour = 0;
        return LocalTime.of(hour, minute);
    }

    private DayOfWeek parseDayOfWeek(String text) {
        String[] names = {"月", "火", "水", "木", "金", "土", "日"};
        for (int i = 0; i < names.length; i++) if (text.contains(names[i])) return DayOfWeek.of(i + 1);
        return null;
    }

    private LocalDate safeDate(int year, int month, int day) {
        try { return LocalDate.of(year, month, day); } catch (DateTimeException e) { return null; }
    }

    private Integer parseNumber(String value) {
        Matcher m = NUMBER.matcher(value);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace("げつようび", "月曜日").replace("かようび", "火曜日")
                .replace("すいようび", "水曜日").replace("もくようび", "木曜日")
                .replace("きんようび", "金曜日").replace("どようび", "土曜日")
                .replace("にちようび", "日曜日")
                .replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record ReminderTitle(String title, String reminders) {}
    private record Row(long id, String title, OffsetDateTime at, String seriesId, String recurrence, String reminders) {}
    private record Recurrence(LocalDate firstDate, LocalTime time, String title, String label,
                              String reminders, java.util.function.Predicate<LocalDate> predicate) {
        boolean matches(LocalDate date) { return predicate.test(date); }
    }
}
