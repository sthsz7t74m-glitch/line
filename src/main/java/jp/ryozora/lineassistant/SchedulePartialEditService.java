package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SchedulePartialEditService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})(?!\\d)");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[/-](\\d{1,2})(?!\\d)");
    private static final Pattern WEEKDAY = Pattern.compile("(?:(今週|来週))?([月火水木金土日])曜(?:日)?");
    private static final Pattern CLOCK = Pattern.compile("(?:(午前|午後)\\s*)?([01]?\\d|2[0-3])(?::|時)([0-5]\\d)?(?:分)?(半)?");

    private final JdbcTemplate jdbc;

    public SchedulePartialEditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String edit(String userId, int number, String raw) {
        ScheduleRow current = rowByNumber(userId, number);
        if (current == null) return "その番号の予定は見つからなかったよ。『予定一覧』で確認してね。";

        Patch patch = parse(raw, current.at().toLocalDate(), current.at().toLocalTime(), current.title());
        if (patch == null || !patch.changed()) {
            return "変更内容を読み取れなかったよ。内容、日付、時刻のどれかを送ってね。";
        }

        LocalDateTime updatedAt = LocalDateTime.of(patch.date(), patch.time());
        jdbc.update("update schedules set title=?, starts_at=? where id=? and line_user_id=?",
                patch.title(), Timestamp.from(updatedAt.atZone(TOKYO).toInstant()), current.id(), userId);

        return "予定を変更したよ。\n"
                + "日付　" + patch.date().format(DateTimeFormatter.ofPattern("yyyy/MM/dd（E）", Locale.JAPANESE)) + "\n"
                + "時間　" + patch.time().format(DateTimeFormatter.ofPattern("H:mm")) + "\n"
                + "内容　" + patch.title();
    }

    Patch parse(String raw, LocalDate currentDate, LocalTime currentTime, String currentTitle) {
        if (raw == null) return null;
        String input = normalize(raw);
        if (input.isBlank()) return null;

        String remainder = input;
        LocalDate date = currentDate;
        LocalTime time = currentTime.withSecond(0).withNano(0);
        boolean dateChanged = false;
        boolean timeChanged = false;

        DateMatch dateMatch = parseDate(remainder);
        if (dateMatch != null) {
            date = dateMatch.date();
            remainder = removeRange(remainder, dateMatch.start(), dateMatch.end());
            dateChanged = true;
        }

        Matcher clock = CLOCK.matcher(remainder);
        if (clock.find()) {
            int hour = Integer.parseInt(clock.group(2));
            int minute = clock.group(4) != null ? 30 : clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3));
            if ("午後".equals(clock.group(1)) && hour < 12) hour += 12;
            if ("午前".equals(clock.group(1)) && hour == 12) hour = 0;
            try {
                time = LocalTime.of(hour, minute);
                remainder = removeRange(remainder, clock.start(), clock.end());
                timeChanged = true;
            } catch (DateTimeException ignored) {
                return null;
            }
        }

        String title = cleanupTitle(remainder);
        boolean titleChanged = !title.isBlank();
        if (!titleChanged) title = currentTitle;

        return new Patch(date, time, title, dateChanged, timeChanged, titleChanged);
    }

    private DateMatch parseDate(String input) {
        LocalDate today = LocalDate.now(TOKYO);
        String lower = input.toLowerCase(Locale.ROOT);
        for (String token : List.of("明後日", "あさって", "明日", "あした", "今日", "きょう")) {
            int index = lower.indexOf(token);
            if (index >= 0) {
                int plus = token.equals("明後日") || token.equals("あさって") ? 2
                        : token.equals("明日") || token.equals("あした") ? 1 : 0;
                return new DateMatch(today.plusDays(plus), index, index + token.length());
            }
        }

        Matcher iso = ISO_DATE.matcher(input);
        if (iso.find()) {
            try {
                return new DateMatch(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3))), iso.start(), iso.end());
            } catch (DateTimeException ignored) {
                return null;
            }
        }

        Matcher shortDate = SHORT_DATE.matcher(input);
        if (shortDate.find()) {
            try {
                LocalDate candidate = LocalDate.of(today.getYear(), Integer.parseInt(shortDate.group(1)),
                        Integer.parseInt(shortDate.group(2)));
                if (candidate.isBefore(today.minusDays(1))) candidate = candidate.plusYears(1);
                return new DateMatch(candidate, shortDate.start(), shortDate.end());
            } catch (DateTimeException ignored) {
                return null;
            }
        }

        Matcher weekday = WEEKDAY.matcher(input);
        if (weekday.find()) {
            java.time.DayOfWeek target = switch (weekday.group(2)) {
                case "月" -> java.time.DayOfWeek.MONDAY;
                case "火" -> java.time.DayOfWeek.TUESDAY;
                case "水" -> java.time.DayOfWeek.WEDNESDAY;
                case "木" -> java.time.DayOfWeek.THURSDAY;
                case "金" -> java.time.DayOfWeek.FRIDAY;
                case "土" -> java.time.DayOfWeek.SATURDAY;
                default -> java.time.DayOfWeek.SUNDAY;
            };
            LocalDate candidate;
            if ("今週".equals(weekday.group(1))) {
                int delta = target.getValue() - today.getDayOfWeek().getValue();
                candidate = delta >= 0 ? today.plusDays(delta) : today.with(TemporalAdjusters.next(target));
            } else if ("来週".equals(weekday.group(1))) {
                LocalDate nextMonday = today.with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
                candidate = nextMonday.plusDays(target.getValue() - 1L);
            } else {
                candidate = today.with(TemporalAdjusters.nextOrSame(target));
            }
            return new DateMatch(candidate, weekday.start(), weekday.end());
        }
        return null;
    }

    private ScheduleRow rowByNumber(String userId, int number) {
        if (number < 1) return null;
        List<ScheduleRow> rows = jdbc.query("""
                select id,title,starts_at
                from schedules
                where line_user_id=? and starts_at>=current_timestamp
                order by starts_at
                limit 100
                """, (rs, i) -> new ScheduleRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atZone(TOKYO).toLocalDateTime()
        ), userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private String normalize(String value) {
        return value.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private String cleanupTitle(String value) {
        return value.replaceAll("^[、,。\\s]+|[、,。\\s]+$", "").strip();
    }

    private String removeRange(String value, int start, int end) {
        return (value.substring(0, start) + " " + value.substring(end)).replaceAll("\\s+", " ").strip();
    }

    record Patch(LocalDate date, LocalTime time, String title,
                 boolean dateChanged, boolean timeChanged, boolean titleChanged) {
        boolean changed() { return dateChanged || timeChanged || titleChanged; }
    }

    private record DateMatch(LocalDate date, int start, int end) { }
    private record ScheduleRow(long id, String title, LocalDateTime at) { }
}