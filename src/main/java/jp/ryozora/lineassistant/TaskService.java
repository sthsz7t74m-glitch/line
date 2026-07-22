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
public class TaskService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final String DEFAULT_REMINDERS = "1440,60,0";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M/d H:mm");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern REMINDER = Pattern.compile("(\\d{1,4})(分|時間|日)前");
    private static final Pattern PRIORITY = Pattern.compile("優先度\\s*(高|中|低)");
    private static final Pattern RELATIVE_HOURS = Pattern.compile("(\\d{1,3})時間後");
    private static final Pattern RELATIVE_DAYS = Pattern.compile("(\\d{1,3})日後");
    private static final Pattern DATE_PREFIX = Pattern.compile(
            "^(今日|明日|明後日|(?:今週|来週)?[月火水木金土日]曜(?:日)?|(?:\\d{4}[/-])?\\d{1,2}[/-]\\d{1,2})"
                    + "(?:(午前|午後)?(\\d{1,2})(?:時|:)(\\d{1,2})?(?:分)?(半)?)?\\s*(.*)$");
    private static final Pattern RECURRING = Pattern.compile(
            "^(毎日|平日|土日|毎週([月火水木金土日])曜?|毎月(\\d{1,2})日)"
                    + "(?:(午前|午後)?(\\d{1,2})(?:時|:)(\\d{1,2})?(?:分)?(半)?)?"
                    + "\\s*(?:までに\\s*)?(.+)$");

    private final JdbcTemplate jdbc;
    private final BenlyStore store;

    public TaskService(JdbcTemplate jdbc, BenlyStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        if (text.isBlank()) return false;
        return text.equals("タスク") || text.equals("タスク一覧")
                || text.equals("今日のタスク") || text.equals("期限切れ") || text.equals("期限切れタスク")
                || text.equals("今週のタスク")
                || text.startsWith("タスク ") || text.startsWith("完了 ") || text.startsWith("タスク完了 ")
                || text.startsWith("タスク完了ID ") || text.startsWith("タスク削除 ")
                || text.startsWith("タスク変更 ") || text.startsWith("タスク延期 ")
                || text.startsWith("タスク延期ID ") || text.startsWith("優先度変更 ")
                || text.startsWith("繰り返しタスク ")
                || isNaturalDeadline(text);
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        store.ensureUser(userId);

        if (text.startsWith("タスク完了ID ")) return completeById(userId, parseLong(text.substring("タスク完了ID ".length())));
        if (text.startsWith("タスク延期ID ")) return postponeByIdCommand(userId, text.substring("タスク延期ID ".length()));
        if (text.startsWith("タスク完了 ")) return completeByNumber(userId, text.substring("タスク完了 ".length()));
        if (text.startsWith("完了 ")) return completeByNumber(userId, text.substring("完了 ".length()));
        if (text.startsWith("タスク削除 ")) return deleteByNumber(userId, text.substring("タスク削除 ".length()));
        if (text.startsWith("タスク変更 ")) return editByNumber(userId, text.substring("タスク変更 ".length()));
        if (text.startsWith("タスク延期 ")) return postponeByNumber(userId, text.substring("タスク延期 ".length()));
        if (text.startsWith("優先度変更 ")) return changePriority(userId, text.substring("優先度変更 ".length()));
        if (text.startsWith("繰り返しタスク ")) return addRecurring(userId, text.substring("繰り返しタスク ".length()));
        if (isRecurringDeadline(text)) return addRecurring(userId, text);

        if (text.equals("今日のタスク")) return listToday(userId);
        if (text.equals("期限切れ") || text.equals("期限切れタスク")) return listOverdue(userId);
        if (text.equals("今週のタスク")) return listWeek(userId);
        if (text.equals("タスク") || text.equals("タスク一覧")) return listAll(userId);
        if (text.startsWith("タスク ")) return addSingle(userId, text.substring("タスク ".length()));
        if (isNaturalDeadline(text)) return addSingle(userId, text);
        return null;
    }

    @Transactional
    String addSingle(String userId, String raw) {
        ParsedTask parsed = parseTask(raw);
        if (parsed == null || parsed.title().isBlank()) {
            return "タスクを読み取れなかったよ。例：明日までに資料作成 / タスク 部屋の掃除 優先度高";
        }
        Long id = jdbc.queryForObject("""
                insert into tasks(line_user_id,title,priority,due_at,reminder_minutes)
                values (?,?,?,?,?) returning id
                """, Long.class, userId, parsed.title(), parsed.priority(), timestamp(parsed.dueAt()), parsed.reminders());
        return addedMessage(id, parsed.title(), parsed.priority(), parsed.dueAt(), parsed.reminders(), null);
    }

    @Transactional
    String addRecurring(String userId, String raw) {
        TaskMetadata metadata = extractMetadata(raw);
        Recurrence recurrence = parseRecurrence(metadata.cleaned());
        if (recurrence == null) {
            return "繰り返しタスクを読み取れなかったよ。例：繰り返しタスク 毎週月曜9時 ゴミ出し確認";
        }
        String seriesId = UUID.randomUUID().toString();
        LocalDate end = LocalDate.now(TOKYO).plusMonths(12);
        int count = 0;
        LocalDate cursor = recurrence.firstDate();
        while (!cursor.isAfter(end) && count < 500) {
            if (recurrence.matches(cursor)) {
                LocalDateTime dueAt = LocalDateTime.of(cursor, recurrence.time());
                jdbc.update("""
                        insert into tasks(line_user_id,title,priority,due_at,reminder_minutes,series_id,recurrence_label)
                        values (?,?,?,?,?,?,?)
                        """, userId, recurrence.title(), metadata.priority(), timestamp(dueAt), metadata.reminders(),
                        seriesId, recurrence.label());
                count++;
            }
            cursor = cursor.plusDays(1);
        }
        return "繰り返しタスクを追加したよ。\n"
                + recurrence.label() + "　" + recurrence.time().format(DateTimeFormatter.ofPattern("H:mm")) + "\n"
                + recurrence.title() + "\n今後12か月分を" + count + "件登録したよ。";
    }

    String listAll(String userId) {
        List<TaskRow> all = pendingRows(userId);
        if (all.isEmpty()) return "未完了タスクはゼロ！すっきりしてるね。";
        return formatRows("未完了タスク", all, all);
    }

    String listToday(String userId) {
        List<TaskRow> all = pendingRows(userId);
        LocalDateTime end = LocalDate.now(TOKYO).plusDays(1).atStartOfDay();
        List<TaskRow> rows = all.stream().filter(row -> row.dueAt() != null && row.dueAt().isBefore(end)).toList();
        if (rows.isEmpty()) return "今日までのタスクはないよ。";
        return formatRows("今日のタスク", rows, all);
    }

    String listOverdue(String userId) {
        List<TaskRow> all = pendingRows(userId);
        LocalDateTime now = LocalDateTime.now(TOKYO);
        List<TaskRow> rows = all.stream().filter(row -> row.dueAt() != null && row.dueAt().isBefore(now)).toList();
        if (rows.isEmpty()) return "期限切れタスクはないよ。いい感じ！";
        return formatRows("期限切れタスク", rows, all);
    }

    String listWeek(String userId) {
        List<TaskRow> all = pendingRows(userId);
        LocalDateTime now = LocalDateTime.now(TOKYO);
        LocalDateTime end = LocalDate.now(TOKYO).plusDays(7).atTime(23, 59);
        List<TaskRow> rows = all.stream().filter(row -> row.dueAt() != null
                && !row.dueAt().isBefore(now) && !row.dueAt().isAfter(end)).toList();
        if (rows.isEmpty()) return "これから7日間が期限のタスクはないよ。";
        return formatRows("今週のタスク", rows, all);
    }

    @Transactional
    String completeByNumber(String userId, String value) {
        TaskRow row = rowByNumber(userId, parseNumber(value));
        if (row == null) return "その番号の未完了タスクは見つからなかったよ。『タスク一覧』で確認してね。";
        return completeById(userId, row.id());
    }

    @Transactional
    String completeById(String userId, Long taskId) {
        if (taskId == null) return "タスクを特定できなかったよ。";
        TaskRow row = rowById(userId, taskId);
        if (row == null) return "その未完了タスクは見つからなかったよ。";
        int changed = jdbc.update("""
                update tasks set completed=true,completed_at=current_timestamp
                where id=? and line_user_id=? and completed=false
                """, taskId, userId);
        if (changed != 1) return "そのタスクはすでに完了しているみたい。";
        addExperience(userId, 10, "TASK_COMPLETED");
        return "タスク「" + row.title() + "」を完了！\n経験値 +10";
    }

    @Transactional
    String deleteByNumber(String userId, String value) {
        TaskRow row = rowByNumber(userId, parseNumber(value));
        if (row == null) return "例：タスク削除 2 のように送ってね。番号は『タスク一覧』で確認できるよ。";
        jdbc.update("delete from notification_delivery_log where line_user_id=? and notification_type='TASK' and target_key like ?",
                userId, row.id() + ":%");
        int changed = jdbc.update("delete from tasks where id=? and line_user_id=?", row.id(), userId);
        return changed == 1 ? "タスク「" + row.title() + "」を削除したよ。" : "タスクを削除できなかったよ。";
    }

    @Transactional
    String editByNumber(String userId, String value) {
        int space = value.indexOf(' ');
        if (space < 1) return "例：タスク変更 2 明日18時 資料提出 優先度高";
        TaskRow old = rowByNumber(userId, parseNumber(value.substring(0, space)));
        if (old == null) return "その番号のタスクは見つからなかったよ。";
        ParsedTask parsed = parseTask(value.substring(space + 1));
        if (parsed == null) return "変更内容を読み取れなかったよ。";
        jdbc.update("""
                update tasks set title=?,priority=?,due_at=?,reminder_minutes=?
                where id=? and line_user_id=?
                """, parsed.title(), parsed.priority(), timestamp(parsed.dueAt()), parsed.reminders(), old.id(), userId);
        clearTaskDeliveries(userId, old.id());
        return "タスクを変更したよ。\n" + taskLine(parsed.title(), parsed.priority(), parsed.dueAt(), null);
    }

    @Transactional
    String postponeByNumber(String userId, String value) {
        int space = value.indexOf(' ');
        if (space < 1) return "例：タスク延期 2 明日 / タスク延期 2 1時間後";
        TaskRow row = rowByNumber(userId, parseNumber(value.substring(0, space)));
        if (row == null) return "その番号のタスクは見つからなかったよ。";
        return postpone(userId, row, value.substring(space + 1));
    }

    @Transactional
    String postponeByIdCommand(String userId, String value) {
        String[] parts = value.split("\\s+", 2);
        if (parts.length != 2) return "タスクを延期できなかったよ。";
        String target = parts[0].equals("60") ? "1時間後" : parts[0];
        TaskRow row = rowById(userId, parseLong(parts[1]));
        if (row == null) return "そのタスクは見つからなかったよ。";
        return postpone(userId, row, target);
    }

    private String postpone(String userId, TaskRow row, String target) {
        LocalDateTime next = resolvePostpone(target, row.dueAt());
        if (next == null) return "延期先を読み取れなかったよ。例：明日 / 1時間後 / 3日後";
        jdbc.update("update tasks set due_at=?,postpone_count=postpone_count+1 where id=? and line_user_id=?",
                timestamp(next), row.id(), userId);
        clearTaskDeliveries(userId, row.id());
        return "タスク「" + row.title() + "」を延期したよ。\n新しい期限　" + next.format(DATE_TIME);
    }

    @Transactional
    String changePriority(String userId, String value) {
        String[] parts = value.split("\\s+", 2);
        if (parts.length != 2) return "例：優先度変更 2 高";
        TaskRow row = rowByNumber(userId, parseNumber(parts[0]));
        String priority = parsePriority(parts[1]);
        if (row == null || priority == null) return "番号または優先度を読み取れなかったよ。高・中・低で指定してね。";
        jdbc.update("update tasks set priority=? where id=? and line_user_id=?", priority, row.id(), userId);
        return "タスク「" + row.title() + "」の優先度を" + priorityLabel(priority) + "に変更したよ。";
    }

    private ParsedTask parseTask(String raw) {
        TaskMetadata metadata = extractMetadata(raw);
        String text = metadata.cleaned();
        if (text.isBlank()) return null;

        LocalDateTime dueAt = null;
        String title = text;

        if (text.startsWith("今日中に")) {
            dueAt = LocalDate.now(TOKYO).atTime(23, 59);
            title = text.substring("今日中に".length()).strip();
        } else if (text.startsWith("明日中に")) {
            dueAt = LocalDate.now(TOKYO).plusDays(1).atTime(23, 59);
            title = text.substring("明日中に".length()).strip();
        } else {
            int marker = text.indexOf("までに");
            if (marker >= 0) {
                dueAt = parseDeadline(text.substring(0, marker).strip());
                title = text.substring(marker + "までに".length()).strip();
                if (dueAt == null) return null;
            } else {
                Matcher prefix = DATE_PREFIX.matcher(text);
                if (prefix.matches()) {
                    dueAt = deadlineFromMatch(prefix);
                    title = prefix.group(6).strip();
                }
            }
        }
        if (title.isBlank()) return null;
        return new ParsedTask(title, metadata.priority(), dueAt, metadata.reminders());
    }

    private TaskMetadata extractMetadata(String raw) {
        String text = normalize(raw);
        String priority = "MEDIUM";
        Matcher priorityMatcher = PRIORITY.matcher(text);
        if (priorityMatcher.find()) {
            priority = switch (priorityMatcher.group(1)) {
                case "高" -> "HIGH";
                case "低" -> "LOW";
                default -> "MEDIUM";
            };
            text = priorityMatcher.replaceAll(" ");
        }

        String reminders = DEFAULT_REMINDERS;
        if (text.contains("通知なし")) {
            reminders = "";
            text = text.replace("通知なし", " ");
        } else {
            LinkedHashSet<Integer> values = new LinkedHashSet<>();
            Matcher matcher = REMINDER.matcher(text);
            while (matcher.find()) {
                int amount = Integer.parseInt(matcher.group(1));
                int minutes = switch (matcher.group(2)) {
                    case "日" -> amount * 1440;
                    case "時間" -> amount * 60;
                    default -> amount;
                };
                if (minutes >= 0 && minutes <= 43200) values.add(minutes);
            }
            if (!values.isEmpty()) {
                reminders = values.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(DEFAULT_REMINDERS);
                text = REMINDER.matcher(text).replaceAll(" ");
            }
        }
        text = text.replaceAll("\\s+", " ").strip();
        return new TaskMetadata(text, priority, reminders);
    }

    private Recurrence parseRecurrence(String raw) {
        Matcher matcher = RECURRING.matcher(raw);
        if (!matcher.matches()) return null;
        String rule = matcher.group(1);
        LocalTime time = parseTime(matcher.group(4), matcher.group(5), matcher.group(6), matcher.group(7), LocalTime.of(23, 59));
        String title = matcher.group(8).strip();
        if (title.isBlank()) return null;
        LocalDate today = LocalDate.now(TOKYO);

        if (rule.equals("毎日")) return new Recurrence(today, time, title, "毎日", d -> true);
        if (rule.equals("平日")) return new Recurrence(today, time, title, "平日", d -> d.getDayOfWeek().getValue() <= 5);
        if (rule.equals("土日")) return new Recurrence(today, time, title, "土日", d -> d.getDayOfWeek().getValue() >= 6);
        if (rule.startsWith("毎週")) {
            DayOfWeek day = dayOfWeek(matcher.group(2));
            if (day == null) return null;
            LocalDate first = today.getDayOfWeek() == day && LocalDateTime.of(today, time).isAfter(LocalDateTime.now(TOKYO))
                    ? today : today.with(TemporalAdjusters.next(day));
            return new Recurrence(first, time, title, "毎週" + matcher.group(2) + "曜", d -> d.getDayOfWeek() == day);
        }
        int day = Integer.parseInt(matcher.group(3));
        if (day < 1 || day > 31) return null;
        LocalDate first = safeDate(YearMonth.from(today), day);
        if (first == null || LocalDateTime.of(first, time).isBefore(LocalDateTime.now(TOKYO))) {
            first = safeDate(YearMonth.from(today).plusMonths(1), day);
        }
        if (first == null) return null;
        return new Recurrence(first, time, title, "毎月" + day + "日", d -> d.getDayOfMonth() == day);
    }

    private LocalDateTime parseDeadline(String phrase) {
        Matcher matcher = DATE_PREFIX.matcher(phrase);
        return matcher.matches() && matcher.group(6).isBlank() ? deadlineFromMatch(matcher) : null;
    }

    private LocalDateTime deadlineFromMatch(Matcher matcher) {
        String dateText = matcher.group(1);
        LocalDate date = resolveDate(dateText);
        if (date == null) return null;
        LocalTime time = parseTime(matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(5), LocalTime.of(23, 59));
        LocalDateTime result = LocalDateTime.of(date, time);
        if (dateText.matches("[月火水木金土日]曜(?:日)?") && result.isBefore(LocalDateTime.now(TOKYO))) {
            result = result.plusWeeks(1);
        }
        return result;
    }

    private LocalDate resolveDate(String value) {
        LocalDate today = LocalDate.now(TOKYO);
        if (value.equals("今日")) return today;
        if (value.equals("明日")) return today.plusDays(1);
        if (value.equals("明後日")) return today.plusDays(2);
        Matcher weekday = Pattern.compile("^(今週|来週)?([月火水木金土日])曜(?:日)?$").matcher(value);
        if (weekday.matches()) {
            DayOfWeek target = dayOfWeek(weekday.group(2));
            if (target == null) return null;
            if ("来週".equals(weekday.group(1))) {
                LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                return monday.plusDays(target.getValue() - 1L);
            }
            if ("今週".equals(weekday.group(1))) {
                int delta = target.getValue() - today.getDayOfWeek().getValue();
                return delta >= 0 ? today.plusDays(delta) : today.plusDays(delta + 7L);
            }
            return today.getDayOfWeek() == target ? today : today.with(TemporalAdjusters.next(target));
        }
        Matcher date = Pattern.compile("^(?:(\\d{4})[/-])?(\\d{1,2})[/-](\\d{1,2})$").matcher(value);
        if (!date.matches()) return null;
        try {
            int year = date.group(1) == null ? today.getYear() : Integer.parseInt(date.group(1));
            LocalDate result = LocalDate.of(year, Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
            if (date.group(1) == null && result.isBefore(today)) result = result.plusYears(1);
            return result;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private LocalTime parseTime(String period, String hourText, String minuteText, String half,
                                LocalTime fallback) {
        if (hourText == null) return fallback;
        int hour = Integer.parseInt(hourText);
        int minute = half != null ? 30 : minuteText == null || minuteText.isBlank() ? 0 : Integer.parseInt(minuteText);
        if ("午後".equals(period) && hour < 12) hour += 12;
        if ("午前".equals(period) && hour == 12) hour = 0;
        try {
            return LocalTime.of(hour, minute);
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    private LocalDateTime resolvePostpone(String raw, LocalDateTime oldDue) {
        String text = normalize(raw);
        LocalDateTime now = LocalDateTime.now(TOKYO).withSecond(0).withNano(0);
        LocalTime oldTime = oldDue == null ? LocalTime.of(9, 0) : oldDue.toLocalTime();
        if (text.equals("明日") || text.equals("明日に")) return LocalDate.now(TOKYO).plusDays(1).atTime(oldTime);
        Matcher hours = RELATIVE_HOURS.matcher(text);
        if (hours.matches()) return now.plusHours(Integer.parseInt(hours.group(1)));
        Matcher days = RELATIVE_DAYS.matcher(text);
        if (days.matches()) return now.plusDays(Integer.parseInt(days.group(1)));
        LocalDateTime parsed = parseDeadline(text);
        return parsed;
    }

    private List<TaskRow> pendingRows(String userId) {
        return jdbc.query("""
                select id,title,priority,due_at,reminder_minutes,series_id,recurrence_label
                from tasks where line_user_id=? and completed=false
                order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                         due_at nulls last, created_at, id
                limit 100
                """, (rs, i) -> mapRow(rs), userId);
    }

    private TaskRow rowByNumber(String userId, Integer number) {
        if (number == null || number < 1) return null;
        List<TaskRow> rows = pendingRows(userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private TaskRow rowById(String userId, Long id) {
        if (id == null) return null;
        return jdbc.query("""
                select id,title,priority,due_at,reminder_minutes,series_id,recurrence_label
                from tasks where id=? and line_user_id=? and completed=false
                """, rs -> rs.next() ? mapRow(rs) : null, id, userId);
    }

    private TaskRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp due = rs.getTimestamp("due_at");
        return new TaskRow(rs.getLong("id"), rs.getString("title"), rs.getString("priority"),
                due == null ? null : due.toInstant().atZone(TOKYO).toLocalDateTime(),
                rs.getString("reminder_minutes"), rs.getString("series_id"), rs.getString("recurrence_label"));
    }

    private String formatRows(String heading, List<TaskRow> rows, List<TaskRow> all) {
        Map<Long, Integer> numbers = new HashMap<>();
        for (int i = 0; i < all.size(); i++) numbers.put(all.get(i).id(), i + 1);
        StringBuilder out = new StringBuilder(heading).append("\n━━━━━━━━━━\n");
        LocalDateTime now = LocalDateTime.now(TOKYO);
        for (TaskRow row : rows) {
            out.append(numbers.get(row.id())).append(".　").append(priorityMark(row.priority())).append(" ")
                    .append(row.title()).append("\n　");
            if (row.dueAt() == null) out.append("期限なし");
            else {
                if (row.dueAt().isBefore(now)) out.append("【期限切れ】");
                out.append(row.dueAt().format(DATE_TIME));
            }
            if (row.recurrence() != null && !row.recurrence().isBlank()) out.append(" / ").append(row.recurrence());
            out.append("\n");
        }
        return out.append("\n完了：完了 1　延期：タスク延期 1 明日").toString().stripTrailing();
    }

    private String addedMessage(Long id, String title, String priority, LocalDateTime dueAt,
                                String reminders, String recurrence) {
        return "タスクを追加したよ。\n" + taskLine(title, priority, dueAt, recurrence)
                + "\n通知　" + reminderLabel(dueAt, reminders)
                + (id == null ? "" : "\n\n『タスク一覧』で番号を確認できるよ。");
    }

    private String taskLine(String title, String priority, LocalDateTime dueAt, String recurrence) {
        return "内容　" + title + "\n優先度　" + priorityLabel(priority) + "\n期限　"
                + (dueAt == null ? "なし" : dueAt.format(DATE_TIME))
                + (recurrence == null ? "" : "\n繰り返し　" + recurrence);
    }

    private String reminderLabel(LocalDateTime dueAt, String reminders) {
        if (dueAt == null) return "期限設定時に有効";
        if (reminders == null || reminders.isBlank()) return "なし";
        return Arrays.stream(reminders.split(",")).map(value -> {
            int minutes = Integer.parseInt(value.strip());
            if (minutes == 0) return "期限時刻";
            if (minutes % 1440 == 0) return (minutes / 1440) + "日前";
            if (minutes % 60 == 0) return (minutes / 60) + "時間前";
            return minutes + "分前";
        }).reduce((a, b) -> a + "・" + b).orElse("なし");
    }

    private void addExperience(String userId, int points, String reason) {
        jdbc.update("update benly_users set experience=experience+?,updated_at=current_timestamp where line_user_id=?",
                points, userId);
        jdbc.update("insert into experience_logs(line_user_id,points,reason) values (?,?,?)", userId, points, reason);
    }

    private void clearTaskDeliveries(String userId, long taskId) {
        jdbc.update("delete from notification_delivery_log where line_user_id=? and notification_type='TASK' and target_key like ?",
                userId, taskId + ":%");
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.from(value.atZone(TOKYO).toInstant());
    }

    private boolean isNaturalDeadline(String text) {
        return text.contains("までに") || text.startsWith("今日中に") || text.startsWith("明日中に");
    }

    private boolean isRecurringDeadline(String text) {
        return isNaturalDeadline(text) && (text.startsWith("毎日") || text.startsWith("平日")
                || text.startsWith("土日") || text.startsWith("毎週") || text.startsWith("毎月"));
    }

    private String parsePriority(String value) {
        String text = normalize(value);
        if (text.contains("高")) return "HIGH";
        if (text.contains("中")) return "MEDIUM";
        if (text.contains("低")) return "LOW";
        return null;
    }

    private String priorityMark(String priority) {
        return switch (priority == null ? "MEDIUM" : priority) {
            case "HIGH" -> "[高]";
            case "LOW" -> "[低]";
            default -> "[中]";
        };
    }

    private String priorityLabel(String priority) {
        return switch (priority == null ? "MEDIUM" : priority) {
            case "HIGH" -> "高";
            case "LOW" -> "低";
            default -> "中";
        };
    }

    private DayOfWeek dayOfWeek(String value) {
        if (value == null) return null;
        int index = "月火水木金土日".indexOf(value);
        return index < 0 ? null : DayOfWeek.of(index + 1);
    }

    private LocalDate safeDate(YearMonth month, int day) {
        try {
            return month.atDay(day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private Integer parseNumber(String value) {
        Matcher matcher = NUMBER.matcher(value == null ? "" : value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record ParsedTask(String title, String priority, LocalDateTime dueAt, String reminders) {}
    private record TaskMetadata(String cleaned, String priority, String reminders) {}
    private record TaskRow(long id, String title, String priority, LocalDateTime dueAt,
                           String reminders, String seriesId, String recurrence) {}
    private record Recurrence(LocalDate firstDate, LocalTime time, String title, String label,
                              java.util.function.Predicate<LocalDate> predicate) {
        boolean matches(LocalDate date) { return predicate.test(date); }
    }
}
