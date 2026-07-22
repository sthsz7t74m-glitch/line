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
    private static final Pattern REMINDER = Pattern.compile("(\\d{1,4})(分|時間|日)前");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern END_DATE = Pattern.compile("(?:終了|まで)\\s*(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})");

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
        if (text.startsWith("繰り返し変更 ")) return changeRecurrence(userId, text.substring(8).strip());
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
        return addedMessage(local, parsed.title(), null, parsed.reminders(), null);
    }

    @Transactional
    String addRecurring(String userId, String text) {
        Recurrence recurrence = parseRecurrence(text);
        if (recurrence == null) return "繰り返し予定を読み取れなかったよ。例：毎週月曜19時 ジム";
        String seriesId = UUID.randomUUID().toString();
        int count = insertSeries(userId, seriesId, recurrence, recurrence.firstDate());
        return addedMessage(LocalDateTime.of(recurrence.firstDate(), recurrence.time()), recurrence.title(),
                recurrence.label(), recurrence.reminders(), recurrence.endDate())
                + "\n\n" + count + "件の予定を登録したよ。";
    }

    private int insertSeries(String userId, String seriesId, Recurrence recurrence, LocalDate start) {
        int count = 0;
        LocalDate cursor = start;
        while (!cursor.isAfter(recurrence.endDate()) && count < 500) {
            if (recurrence.matches(cursor)) {
                LocalDateTime at = LocalDateTime.of(cursor, recurrence.time());
                jdbc.update("insert into schedules(line_user_id,title,starts_at,series_id,recurrence_label,reminder_minutes) values (?,?,?,?,?,?)",
                        userId, recurrence.title(), Timestamp.from(at.atZone(TOKYO).toInstant()), seriesId,
                        recurrence.label(), recurrence.reminders());
                count++;
            }
            cursor = cursor.plusDays(1);
        }
        return count;
    }

    private Recurrence parseRecurrence(String rawText) {
        String text = rawText;
        LocalDate explicitEnd = extractEndDate(text);
        if (explicitEnd != null) text = END_DATE.matcher(text).replaceAll("").strip();

        Matcher tm = TIME.matcher(text);
        if (!tm.find()) return null;
        LocalTime time = parseTime(tm);
        String prefix = text.substring(0, tm.start()).strip();
        ReminderTitle rt = parseReminderTitle(text.substring(tm.end()).strip());
        if (rt.title().isBlank()) return null;
        LocalDate today = LocalDate.now(TOKYO);
        LocalDate end = explicitEnd != null ? explicitEnd : today.plusMonths(12);
        if (end.isBefore(today)) return null;

        if (prefix.startsWith("毎日")) {
            return new Recurrence(today, time, rt.title(), "毎日", rt.reminders(), end, d -> true);
        }
        if (prefix.startsWith("平日")) {
            return new Recurrence(today, time, rt.title(), "平日", rt.reminders(), end,
                    d -> d.getDayOfWeek().getValue() <= 5);
        }
        if (prefix.startsWith("土日")) {
            return new Recurrence(today, time, rt.title(), "土日", rt.reminders(), end,
                    d -> d.getDayOfWeek().getValue() >= 6);
        }
        if (prefix.startsWith("毎週")) {
            DayOfWeek dow = parseDayOfWeek(prefix);
            if (dow == null) return null;
            LocalDate first = today.getDayOfWeek() == dow ? today : today.with(TemporalAdjusters.next(dow));
            String label = "毎週" + dow.getDisplayName(TextStyle.SHORT, Locale.JAPANESE) + "曜";
            return new Recurrence(first, time, rt.title(), label, rt.reminders(), end,
                    d -> d.getDayOfWeek() == dow);
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
            return new Recurrence(first, time, rt.title(), label, rt.reminders(), end,
                    d -> d.getDayOfMonth() == day);
        }
        return null;
    }

    private LocalDate extractEndDate(String text) {
        Matcher matcher = END_DATE.matcher(text);
        if (!matcher.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private String listUpcoming(String userId) {
        List<Row> rows = upcomingRows(userId);
        if (rows.isEmpty()) return "今後の予定はまだないよ。";
        StringBuilder out = new StringBuilder("今後の予定\n");
        LocalDate previous = null;
        int number = 1;
        for (Row row : rows) {
            LocalDate date = row.at().toLocalDate();
            if (!date.equals(previous)) {
                out.append("\n").append(relative(date)).append("　")
                        .append(date.format(DateTimeFormatter.ofPattern("M/d")))
                        .append("（").append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE)).append("）\n");
                previous = date;
            }
            out.append(number++).append(".　")
                    .append(row.at().format(DateTimeFormatter.ofPattern("HH:mm"))).append("　")
                    .append(row.title());
            if (row.recurrence() != null) out.append("　［").append(row.recurrence()).append("］");
            out.append("\n");
        }
        return out.toString().stripTrailing();
    }

    @Transactional
    String delete(String userId, String value) {
        ScopeInput input = parseScopeInput(value);
        if (input.number() == null) return "例：予定削除 2 この回だけ のように送ってね。";
        Row row = rowByDisplayNumber(userId, input.number());
        if (row == null) return "その番号の予定は見つからなかったよ。『予定一覧』で確認してね。";
        if (row.seriesId() == null || row.seriesId().isBlank() || input.scope() == Scope.ONE) {
            int changed = jdbc.update("delete from schedules where line_user_id=? and id=?", userId, row.id());
            return changed == 1 ? "予定「" + row.title() + "」を削除したよ。" : "予定を削除できなかったよ。";
        }
        int changed;
        if (input.scope() == Scope.ALL) {
            changed = jdbc.update("delete from schedules where line_user_id=? and series_id=?", userId, row.seriesId());
            return "繰り返し予定「" + row.title() + "」を全部削除したよ（" + changed + "件）。";
        }
        changed = jdbc.update("delete from schedules where line_user_id=? and series_id=? and starts_at>=?",
                userId, row.seriesId(), Timestamp.from(row.at().toInstant()));
        return "繰り返し予定「" + row.title() + "」を、この回以降削除したよ（" + changed + "件）。";
    }

    @Transactional
    String change(String userId, String value) {
        ScopeInput input = parseScopeInput(value);
        if (input.number() == null) return "例：予定変更 2 2026-07-30 20:00 会議 この回以降";
        Row row = rowByDisplayNumber(userId, input.number());
        if (row == null) return "その番号の予定は見つからなかったよ。";
        String rest = input.rest();
        Matcher m = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+(.+)$").matcher(rest);
        if (!m.matches()) return "変更は『予定変更 2 2026-07-30 20:00 会議』の形で送ってね。";
        LocalDateTime local = LocalDateTime.parse(m.group(1), INPUT);
        String title = m.group(2).strip();
        if (row.seriesId() != null && !row.seriesId().isBlank() && input.scope() != Scope.ONE) {
            Timestamp boundary = input.scope() == Scope.ALL ? Timestamp.from(Instant.EPOCH) : Timestamp.from(row.at().toInstant());
            jdbc.update("update schedules set title=? where line_user_id=? and series_id=? and starts_at>=?",
                    title, userId, row.seriesId(), boundary);
            jdbc.update("update schedules set starts_at=? where line_user_id=? and id=?",
                    Timestamp.from(local.atZone(TOKYO).toInstant()), userId, row.id());
        } else {
            jdbc.update("update schedules set starts_at=?,title=? where line_user_id=? and id=?",
                    Timestamp.from(local.atZone(TOKYO).toInstant()), title, userId, row.id());
        }
        return "予定を変更したよ。\n" + addedMessage(local, title, row.recurrence(), row.reminders(), null);
    }

    @Transactional
    String changeRecurrence(String userId, String value) {
        int space = value.indexOf(' ');
        if (space < 1) return "例：繰り返し変更 2 毎週火曜19時 ジム 終了2026/12/31";
        Integer number = parseNumber(value.substring(0, space));
        Row row = number == null ? null : rowByDisplayNumber(userId, number);
        if (row == null || row.seriesId() == null || row.seriesId().isBlank()) {
            return "その番号の繰り返し予定は見つからなかったよ。";
        }
        Recurrence recurrence = parseRecurrence(value.substring(space + 1).strip());
        if (recurrence == null) return "新しい繰り返し方を読み取れなかったよ。";
        jdbc.update("delete from schedules where line_user_id=? and series_id=? and starts_at>=?",
                userId, row.seriesId(), Timestamp.from(row.at().toInstant()));
        LocalDate start = recurrence.firstDate().isBefore(row.at().toLocalDate()) ? row.at().toLocalDate() : recurrence.firstDate();
        int count = insertSeries(userId, row.seriesId(), recurrence, start);
        return "繰り返し予定を変更したよ。\n" + recurrence.label() + "／終了 "
                + recurrence.endDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "／" + count + "件";
    }

    @Transactional
    String changeReminders(String userId, String value) {
        ScopeInput input = parseScopeInput(value);
        if (input.number() == null) return "例：通知変更 2 30分前と10分前 この回以降";
        Row row = rowByDisplayNumber(userId, input.number());
        if (row == null) return "その番号の予定は見つからなかったよ。";
        String reminders = parseReminders(input.rest());
        if (row.seriesId() != null && !row.seriesId().isBlank() && input.scope() != Scope.ONE) {
            if (input.scope() == Scope.ALL) {
                jdbc.update("update schedules set reminder_minutes=? where line_user_id=? and series_id=?",
                        reminders, userId, row.seriesId());
            } else {
                jdbc.update("update schedules set reminder_minutes=? where line_user_id=? and series_id=? and starts_at>=?",
                        reminders, userId, row.seriesId(), Timestamp.from(row.at().toInstant()));
            }
        } else {
            jdbc.update("update schedules set reminder_minutes=? where line_user_id=? and id=?", reminders, userId, row.id());
        }
        return "通知タイミングを変更したよ。\n通知　" + reminderLabel(reminders);
    }

    private List<Row> upcomingRows(String userId) {
        return jdbc.query("""
                select id,title,starts_at,series_id,recurrence_label,reminder_minutes
                from schedules where line_user_id=? and starts_at>=current_timestamp
                order by starts_at limit 30
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atOffset(OFFSET), rs.getString("series_id"),
                rs.getString("recurrence_label"), rs.getString("reminder_minutes")), userId);
    }

    private Row rowByDisplayNumber(String userId, int number) {
        if (number < 1) return null;
        List<Row> rows = upcomingRows(userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private ScopeInput parseScopeInput(String value) {
        String normalized = value.strip();
        Scope scope = Scope.ONE;
        if (normalized.contains("この回以降") || normalized.contains("今後すべて")) scope = Scope.FUTURE;
        if (normalized.contains("全部") || normalized.contains("シリーズ全体")) scope = Scope.ALL;
        normalized = normalized.replace("この回だけ", "").replace("この回以降", "")
                .replace("今後すべて", "").replace("全部", "").replace("シリーズ全体", "")
                .replaceAll("\\s+", " ").strip();
        int space = normalized.indexOf(' ');
        String numberText = space < 0 ? normalized : normalized.substring(0, space);
        Integer number = parseNumber(numberText);
        String rest = space < 0 ? "" : normalized.substring(space + 1).strip();
        return new ScopeInput(number, rest, scope);
    }

    private ReminderTitle parseReminderTitle(String raw) {
        String reminders = parseReminders(raw);
        String title = raw.replaceAll("\\d{1,4}(?:分|時間|日)前", "")
                .replaceAll("通知\\d+回", "")
                .replace("通知なし", "")
                .replace("と", " ").replaceAll("\\s+", " ").strip();
        return new ReminderTitle(title, reminders);
    }

    private String parseReminders(String raw) {
        if (raw.contains("通知なし") || raw.equals("なし")) return "";
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        Matcher matcher = REMINDER.matcher(raw);
        while (matcher.find()) {
            int amount = Integer.parseInt(matcher.group(1));
            int minutes = switch (matcher.group(2)) {
                case "日" -> amount * 24 * 60;
                case "時間" -> amount * 60;
                default -> amount;
            };
            values.add(minutes);
        }
        Matcher count = Pattern.compile("通知(\\d+)回").matcher(raw);
        if (values.isEmpty() && count.find()) {
            int n = Math.max(1, Math.min(5, Integer.parseInt(count.group(1))));
            int[] defaults = {30, 10, 5, 1, 0};
            for (int i = 0; i < n; i++) values.add(defaults[i]);
        }
        if (values.isEmpty()) values.add(30);
        return values.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("30");
    }

    private String addedMessage(LocalDateTime local, String title, String recurrence, String reminders, LocalDate endDate) {
        String dayPart = local.getHour() < 11 ? "朝" : local.getHour() < 15 ? "昼" : local.getHour() < 18 ? "夕方" : "夜";
        return "━━━━━━━━━━━━\n予定を追加したよ！\n━━━━━━━━━━━━\n\n"
                + "日付　" + local.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "（"
                + local.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE) + "）\n"
                + "　　　" + relative(local.toLocalDate()) + "\n\n"
                + "時間　" + dayPart + "　" + local.format(DateTimeFormatter.ofPattern("HH:mm")) + "\n"
                + "内容　" + title + "\n"
                + (recurrence == null ? "" : "繰り返し　" + recurrence + "\n")
                + (endDate == null ? "" : "終了日　" + endDate.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "\n")
                + "通知　" + reminderLabel(reminders) + "\n━━━━━━━━━━━━";
    }

    private String reminderLabel(String reminders) {
        if (reminders == null || reminders.isBlank()) return "なし";
        return Arrays.stream(reminders.split(",")).map(v -> {
                    int minutes = Integer.parseInt(v);
                    if (minutes == 0) return "開始時";
                    if (minutes % 1440 == 0) return (minutes / 1440) + "日前";
                    if (minutes % 60 == 0) return (minutes / 60) + "時間前";
                    return minutes + "分前";
                })
                .reduce((a, b) -> a + "・" + b).orElse("30分前");
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

    private enum Scope { ONE, FUTURE, ALL }
    private record ScopeInput(Integer number, String rest, Scope scope) {}
    private record ReminderTitle(String title, String reminders) {}
    private record Row(long id, String title, OffsetDateTime at, String seriesId, String recurrence, String reminders) {}
    private record Recurrence(LocalDate firstDate, LocalTime time, String title, String label,
                              String reminders, LocalDate endDate, java.util.function.Predicate<LocalDate> predicate) {
        boolean matches(LocalDate date) { return predicate.test(date); }
    }
}
