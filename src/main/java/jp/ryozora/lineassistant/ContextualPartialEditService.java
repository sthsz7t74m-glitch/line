package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ContextualPartialEditService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Pattern DATE = Pattern.compile("(今日|明日|明後日|昨日|(?:\\d{4}[/-])?\\d{1,2}[/-]\\d{1,2})");
    private static final Pattern TIME = Pattern.compile("(?:(午前|午後)\\s*)?(\\d{1,2})(?::|時)(\\d{1,2})?(?:分)?(半)?");
    private static final Pattern PRIORITY = Pattern.compile("(?:優先度\\s*)?(高|中|低)");
    private static final Pattern AMOUNT = Pattern.compile("(?<!\\d)([\\d,]{1,12})(?:円|えん)?(?!\\d)");
    private static final List<String> CATEGORIES = List.of("食費", "交通費", "日用品", "娯楽", "医療", "住居", "通信", "その他");

    private final JdbcTemplate jdbc;

    public ContextualPartialEditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String editTask(String userId, int number, String raw) {
        TaskRow old = taskByNumber(userId, number);
        if (old == null) return "その番号のタスクは見つからなかったよ。";

        String input = normalize(raw);
        if (input.isBlank()) return "変更内容を送ってね。";

        String priority = old.priority();
        Matcher priorityMatcher = PRIORITY.matcher(input);
        if (priorityMatcher.find() && (input.contains("優先度") || input.matches("^(高|中|低)$"))) {
            priority = switch (priorityMatcher.group(1)) {
                case "高" -> "HIGH";
                case "低" -> "LOW";
                default -> "MEDIUM";
            };
            input = removeRange(input, priorityMatcher.start(), priorityMatcher.end());
        }

        LocalDateTime due = old.dueAt();
        Matcher dateMatcher = DATE.matcher(input);
        String dateToken = dateMatcher.find() ? dateMatcher.group(1) : null;
        if (dateToken != null) input = removeRange(input, dateMatcher.start(), dateMatcher.end());

        Matcher timeMatcher = TIME.matcher(input);
        LocalTime time = null;
        if (timeMatcher.find()) {
            time = parseTime(timeMatcher);
            input = removeRange(input, timeMatcher.start(), timeMatcher.end());
        }

        if (dateToken != null || time != null) {
            LocalDate date = dateToken == null
                    ? (due == null ? LocalDate.now(TOKYO) : due.toLocalDate())
                    : parseDate(dateToken, LocalDate.now(TOKYO));
            LocalTime resolvedTime = time == null
                    ? (due == null ? LocalTime.of(23, 59) : due.toLocalTime())
                    : time;
            if (date == null || resolvedTime == null) return "日付か時刻を読み取れなかったよ。";
            due = LocalDateTime.of(date, resolvedTime);
        }

        String title = cleanText(input);
        if (title.isBlank()) title = old.title();
        if (title.equals(old.title()) && equalsDateTime(due, old.dueAt()) && priority.equals(old.priority())) {
            return "変更する項目を読み取れなかったよ。内容・日付・時刻・優先度のどれかを送ってね。";
        }

        jdbc.update("""
                update tasks set title=?,priority=?,due_at=?
                where id=? and line_user_id=? and completed=false
                """, title, priority, due == null ? null : Timestamp.valueOf(due), old.id(), userId);

        return "タスクを変更したよ。\n"
                + "変更前　" + taskSummary(old.title(), old.priority(), old.dueAt()) + "\n"
                + "変更後　" + taskSummary(title, priority, due);
    }

    @Transactional
    public String editHabit(String userId, int number, String raw) {
        HabitRow old = habitByNumber(userId, number);
        if (old == null) return "その番号の習慣は見つからなかったよ。";

        String input = normalize(raw);
        if (input.isBlank()) return "変更内容を送ってね。";

        String days = old.days();
        String dayToken = extractDayToken(input);
        if (dayToken != null) {
            days = parseDays(dayToken);
            input = input.replaceFirst(Pattern.quote(dayToken), " ");
        }

        LocalTime reminder = old.reminder();
        boolean reminderSpecified = false;
        if (input.contains("通知なし")) {
            reminder = null;
            reminderSpecified = true;
            input = input.replace("通知なし", " ");
        } else {
            Matcher timeMatcher = TIME.matcher(input);
            if (timeMatcher.find()) {
                reminder = parseTime(timeMatcher);
                reminderSpecified = true;
                input = removeRange(input, timeMatcher.start(), timeMatcher.end());
            }
        }

        String name = cleanText(input);
        if (name.isBlank()) name = old.name();
        if (name.equals(old.name()) && days.equals(old.days()) && java.util.Objects.equals(reminder, old.reminder())) {
            return "変更する項目を読み取れなかったよ。内容・曜日・通知時刻のどれかを送ってね。";
        }

        jdbc.update("""
                update habits set name=?,active_days=?,reminder_time=?,updated_at=current_timestamp
                where id=? and line_user_id=?
                """, name, days, reminderSpecified && reminder == null ? null : (reminder == null ? null : Time.valueOf(reminder)), old.id(), userId);

        return "習慣を変更したよ。\n"
                + "変更前　" + habitSummary(old.name(), old.days(), old.reminder()) + "\n"
                + "変更後　" + habitSummary(name, days, reminder);
    }

    @Transactional
    public String editExpense(String userId, int number, String raw) {
        ExpenseRow old = expenseByNumber(userId, number);
        if (old == null) return "その番号の支出は見つからなかったよ。";

        String input = normalize(raw);
        if (input.isBlank()) return "変更内容を送ってね。";

        LocalDate spentOn = old.spentOn();
        Matcher dateMatcher = DATE.matcher(input);
        if (dateMatcher.find()) {
            LocalDate parsed = parseDate(dateMatcher.group(1), LocalDate.now(TOKYO));
            if (parsed == null) return "日付を読み取れなかったよ。";
            spentOn = parsed;
            input = removeRange(input, dateMatcher.start(), dateMatcher.end());
        }

        String category = old.category();
        for (String candidate : CATEGORIES) {
            if (input.contains(candidate)) {
                category = candidate;
                input = input.replace(candidate, " ").replace("分類", " ");
                break;
            }
        }

        int amount = old.amount();
        Matcher amountMatcher = AMOUNT.matcher(input);
        if (amountMatcher.find() && (input.contains("円") || input.matches(".*(^|\\D)[\\d,]+($|\\D).*") )) {
            try {
                long parsed = Long.parseLong(amountMatcher.group(1).replace(",", ""));
                if (parsed <= 0 || parsed > 100_000_000) return "金額を読み取れなかったよ。";
                amount = (int) parsed;
                input = removeRange(input, amountMatcher.start(), amountMatcher.end());
            } catch (NumberFormatException e) {
                return "金額を読み取れなかったよ。";
            }
        }

        String description = cleanText(input.replace("内容", " "));
        if (description.isBlank()) description = old.description();
        if (description.equals(old.description()) && amount == old.amount()
                && category.equals(old.category()) && spentOn.equals(old.spentOn())) {
            return "変更する項目を読み取れなかったよ。内容・金額・日付・分類のどれかを送ってね。";
        }

        jdbc.update("""
                update expenses set amount=?,description=?,category=?,spent_on=?,updated_at=current_timestamp
                where id=? and line_user_id=?
                """, amount, description, category, spentOn, old.id(), userId);

        return "支出を変更したよ。\n"
                + "変更前　" + expenseSummary(old.description(), old.amount(), old.category(), old.spentOn()) + "\n"
                + "変更後　" + expenseSummary(description, amount, category, spentOn);
    }

    private TaskRow taskByNumber(String userId, int number) {
        if (number < 1) return null;
        List<TaskRow> rows = jdbc.query("""
                select id,title,priority,due_at from tasks
                where line_user_id=? and completed=false
                order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                         due_at nulls last,created_at
                limit 100
                """, (rs, i) -> new TaskRow(rs.getLong("id"), rs.getString("title"), rs.getString("priority"),
                rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toLocalDateTime()), userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private HabitRow habitByNumber(String userId, int number) {
        if (number < 1) return null;
        List<HabitRow> rows = jdbc.query("""
                select id,name,active_days,reminder_time from habits
                where line_user_id=? order by active desc,id
                """, (rs, i) -> new HabitRow(rs.getLong("id"), rs.getString("name"), rs.getString("active_days"),
                rs.getTime("reminder_time") == null ? null : rs.getTime("reminder_time").toLocalTime()), userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private ExpenseRow expenseByNumber(String userId, int number) {
        if (number < 1) return null;
        List<ExpenseRow> rows = jdbc.query("""
                select id,amount,description,category,spent_on from expenses
                where line_user_id=? order by spent_on desc,id desc limit 100
                """, (rs, i) -> new ExpenseRow(rs.getLong("id"), rs.getInt("amount"), rs.getString("description"),
                rs.getString("category"), rs.getDate("spent_on").toLocalDate()), userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private String extractDayToken(String input) {
        for (String token : List.of("毎日", "平日", "土日")) if (input.contains(token)) return token;
        Matcher trailing = Pattern.compile("([月火水木金土日]{2,7})(?:曜(?:日)?)?").matcher(input);
        String found = null;
        while (trailing.find()) found = trailing.group();
        return found;
    }

    private String parseDays(String token) {
        if (token.startsWith("毎日")) return "1234567";
        if (token.startsWith("平日")) return "12345";
        if (token.startsWith("土日")) return "67";
        String names = "月火水木金土日";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.length(); i++) if (token.indexOf(names.charAt(i)) >= 0) out.append(i + 1);
        return out.isEmpty() ? "1234567" : out.toString();
    }

    private LocalDate parseDate(String token, LocalDate today) {
        if (token == null) return null;
        return switch (token) {
            case "今日" -> today;
            case "明日" -> today.plusDays(1);
            case "明後日" -> today.plusDays(2);
            case "昨日" -> today.minusDays(1);
            default -> parseNumericDate(token, today);
        };
    }

    private LocalDate parseNumericDate(String token, LocalDate today) {
        String[] parts = token.replace('-', '/').split("/");
        try {
            if (parts.length == 3) return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            LocalDate date = LocalDate.of(today.getYear(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            if (date.isBefore(today.minusMonths(6))) date = date.plusYears(1);
            return date;
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private LocalTime parseTime(Matcher matcher) {
        try {
            int hour = Integer.parseInt(matcher.group(2));
            int minute = matcher.group(4) != null ? 30 : (matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
            if ("午後".equals(matcher.group(1)) && hour < 12) hour += 12;
            if ("午前".equals(matcher.group(1)) && hour == 12) hour = 0;
            return LocalTime.of(hour, minute);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String removeRange(String value, int start, int end) {
        return (value.substring(0, start) + " " + value.substring(end)).replaceAll("\\s+", " ").strip();
    }

    private String cleanText(String value) {
        return value == null ? "" : value.replaceAll("^[・,:：]+|[・,:：]+$", "").replaceAll("\\s+", " ").strip();
    }

    private boolean equalsDateTime(LocalDateTime a, LocalDateTime b) {
        return java.util.Objects.equals(a, b);
    }

    private String taskSummary(String title, String priority, LocalDateTime due) {
        String priorityLabel = switch (priority == null ? "MEDIUM" : priority) {
            case "HIGH" -> "高";
            case "LOW" -> "低";
            default -> "中";
        };
        String deadline = due == null ? "期限なし" : due.format(DateTimeFormatter.ofPattern("M/d H:mm"));
        return title + " / " + deadline + " / 優先度" + priorityLabel;
    }

    private String habitSummary(String name, String days, LocalTime reminder) {
        return name + " / " + daysLabel(days) + " / "
                + (reminder == null ? "通知なし" : reminder.format(DateTimeFormatter.ofPattern("H:mm")));
    }

    private String daysLabel(String days) {
        if ("1234567".equals(days)) return "毎日";
        if ("12345".equals(days)) return "平日";
        if ("67".equals(days)) return "土日";
        String[] labels = {"月", "火", "水", "木", "金", "土", "日"};
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 7; i++) if (days != null && days.contains(String.valueOf(i))) out.add(labels[i - 1]);
        return String.join("・", out) + "曜";
    }

    private String expenseSummary(String description, int amount, String category, LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("M/d")) + " / " + description + " / "
                + String.format(Locale.JAPAN, "%,d円", amount) + " / " + category;
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record TaskRow(long id, String title, String priority, LocalDateTime dueAt) { }
    private record HabitRow(long id, String name, String days, LocalTime reminder) { }
    private record ExpenseRow(long id, int amount, String description, String category, LocalDate spentOn) { }
}
