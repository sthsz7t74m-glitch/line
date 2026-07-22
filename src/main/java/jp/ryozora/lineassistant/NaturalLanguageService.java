package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NaturalLanguageService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter SCHEDULE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern TIME = Pattern.compile("(?:(午前|午後)\\s*)?([01]?\\d|2[0-3])(?:時|:)([0-5]?\\d)?(?:分|半)?");
    private static final Pattern SLASH_DATE = Pattern.compile("(?<!\\d)(?:(\\d{4})[/-])?(\\d{1,2})[/-](\\d{1,2})(?!\\d)");
    private static final Pattern JP_DATE = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern AFTER_MINUTES = Pattern.compile("(\\d{1,3})分後");
    private static final Pattern AFTER_HOURS = Pattern.compile("(\\d{1,2})時間後");
    private static final Pattern AFTER_DAYS = Pattern.compile("(\\d{1,3})日後");

    private static final Map<String, String> WORD_NORMALIZATIONS = new LinkedHashMap<>();

    static {
        // Longer expressions first so shorter replacements do not break them.
        WORD_NORMALIZATIONS.put("しあさって", "明々後日");
        WORD_NORMALIZATIONS.put("明明後日", "明々後日");
        WORD_NORMALIZATIONS.put("あさって", "明後日");
        WORD_NORMALIZATIONS.put("あした", "明日");
        WORD_NORMALIZATIONS.put("あす", "明日");
        WORD_NORMALIZATIONS.put("きょう", "今日");
        WORD_NORMALIZATIONS.put("きょー", "今日");
        WORD_NORMALIZATIONS.put("さらいしゅう", "再来週");
        WORD_NORMALIZATIONS.put("らいしゅう", "来週");
        WORD_NORMALIZATIONS.put("こんしゅう", "今週");
        WORD_NORMALIZATIONS.put("らいげつ", "来月");
        WORD_NORMALIZATIONS.put("こんげつ", "今月");

        WORD_NORMALIZATIONS.put("げつようび", "月曜日");
        WORD_NORMALIZATIONS.put("かようび", "火曜日");
        WORD_NORMALIZATIONS.put("すいようび", "水曜日");
        WORD_NORMALIZATIONS.put("もくようび", "木曜日");
        WORD_NORMALIZATIONS.put("きんようび", "金曜日");
        WORD_NORMALIZATIONS.put("どようび", "土曜日");
        WORD_NORMALIZATIONS.put("にちようび", "日曜日");
        WORD_NORMALIZATIONS.put("げつよう", "月曜");
        WORD_NORMALIZATIONS.put("かよう", "火曜");
        WORD_NORMALIZATIONS.put("すいよう", "水曜");
        WORD_NORMALIZATIONS.put("もくよう", "木曜");
        WORD_NORMALIZATIONS.put("きんよう", "金曜");
        WORD_NORMALIZATIONS.put("どよう", "土曜");
        WORD_NORMALIZATIONS.put("にちよう", "日曜");

        WORD_NORMALIZATIONS.put("ごぜん", "午前");
        WORD_NORMALIZATIONS.put("ごご", "午後");
        WORD_NORMALIZATIONS.put("しょうご", "正午");
        WORD_NORMALIZATIONS.put("ゆうがた", "夕方");
        WORD_NORMALIZATIONS.put("よる", "夜");
        WORD_NORMALIZATIONS.put("ひる", "昼");
        WORD_NORMALIZATIONS.put("あさ", "朝");
        WORD_NORMALIZATIONS.put("しゅうじつ", "終日");
    }

    public Interpretation interpret(String text) {
        if (text == null || text.isBlank()) return null;

        String normalized = normalizeForParsing(text);
        LocalDateTime relative = extractRelativeDateTime(normalized);
        LocalDate date = relative != null ? relative.toLocalDate() : extractDate(normalized);
        LocalTime time = relative != null ? relative.toLocalTime() : extractTime(normalized);

        if (date != null || time != null || containsAny(normalized, "朝", "昼", "夕方", "夜", "正午", "終日")) {
            LocalDate resolvedDate = date != null ? date : LocalDate.now(TOKYO);
            LocalTime resolvedTime = time != null ? time : defaultTime(normalized);
            if (date == null && resolvedTime.isBefore(LocalTime.now(TOKYO)) && !normalized.contains("今日")) {
                resolvedDate = resolvedDate.plusDays(1);
            }
            String title = removeDateTimeWords(normalized).strip();
            if (!title.isBlank()) {
                LocalDateTime startsAt = LocalDateTime.of(resolvedDate, resolvedTime);
                return new Interpretation(Type.SCHEDULE,
                        "予定 " + startsAt.format(SCHEDULE_FORMAT) + " " + title,
                        "予定として登録するで！");
            }
        }

        if (containsAny(normalized, "買う", "買って", "切れた", "なくなった", "補充", "購入")) {
            String item = normalized
                    .replace("買って", "").replace("買う", "").replace("切れた", "")
                    .replace("なくなった", "").replace("補充", "").replace("購入", "")
                    .replace("を", "").strip();
            if (!item.isBlank()) {
                return new Interpretation(Type.SHOPPING, "買い物 " + item, "買い物リストに入れるで！");
            }
        }

        if (containsAny(normalized, "忘れない", "忘れそう", "やる", "しなきゃ", "すること", "あとで")) {
            String task = normalized
                    .replace("忘れないように", "").replace("忘れない", "")
                    .replace("忘れそう", "").replace("しなきゃ", "")
                    .replace("すること", "").strip();
            if (!task.isBlank()) {
                return new Interpretation(Type.TASK, "タスク " + task, "タスクとして登録するで！");
            }
        }
        return null;
    }

    private String normalizeForParsing(String raw) {
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('\u3000', ' ')
                .replace('：', ':')
                .replace('／', '/')
                .replaceAll("[\\t\\n\\r ]+", " ")
                .strip();
        for (Map.Entry<String, String> entry : WORD_NORMALIZATIONS.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        return value;
    }

    private LocalDateTime extractRelativeDateTime(String text) {
        LocalDateTime now = LocalDateTime.now(TOKYO);
        Matcher minutes = AFTER_MINUTES.matcher(text);
        if (minutes.find()) return now.plusMinutes(Integer.parseInt(minutes.group(1))).withSecond(0).withNano(0);
        Matcher hours = AFTER_HOURS.matcher(text);
        if (hours.find()) return now.plusHours(Integer.parseInt(hours.group(1))).withSecond(0).withNano(0);
        Matcher days = AFTER_DAYS.matcher(text);
        if (days.find()) return now.plusDays(Integer.parseInt(days.group(1))).withSecond(0).withNano(0);
        return null;
    }

    private LocalDate extractDate(String text) {
        LocalDate today = LocalDate.now(TOKYO);
        if (text.contains("明々後日")) return today.plusDays(3);
        if (text.contains("明後日")) return today.plusDays(2);
        if (text.contains("明日")) return today.plusDays(1);
        if (text.contains("今日")) return today;

        Matcher slash = SLASH_DATE.matcher(text);
        if (slash.find()) return resolveDate(today, slash.group(1), slash.group(2), slash.group(3));
        Matcher jp = JP_DATE.matcher(text);
        if (jp.find()) return resolveDate(today, jp.group(1), jp.group(2), jp.group(3));

        String[] names = {"月曜", "火曜", "水曜", "木曜", "金曜", "土曜", "日曜"};
        DayOfWeek[] days = DayOfWeek.values();
        for (int i = 0; i < names.length; i++) {
            if (!text.contains(names[i])) continue;
            LocalDate next = today.with(TemporalAdjusters.next(days[i]));
            if (text.contains("今週")) {
                int delta = days[i].getValue() - today.getDayOfWeek().getValue();
                if (delta >= 0) next = today.plusDays(delta);
            } else if (text.contains("再来週")) {
                LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                next = nextMonday.plusWeeks(1).plusDays(days[i].getValue() - 1L);
            } else if (text.contains("来週")) {
                LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                next = nextMonday.plusDays(days[i].getValue() - 1L);
            }
            return next;
        }
        return null;
    }

    private LocalDate resolveDate(LocalDate today, String yearText, String monthText, String dayText) {
        int year = yearText == null ? today.getYear() : Integer.parseInt(yearText);
        int month = Integer.parseInt(monthText);
        int day = Integer.parseInt(dayText);
        try {
            LocalDate result = LocalDate.of(year, month, day);
            if (yearText == null && result.isBefore(today)) result = result.plusYears(1);
            return result;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private LocalTime extractTime(String text) {
        if (text.contains("正午")) return LocalTime.NOON;
        Matcher matcher = TIME.matcher(text);
        if (!matcher.find()) return null;
        String period = matcher.group(1);
        int hour = Integer.parseInt(matcher.group(2));
        String matched = matcher.group();
        int minute = matched.endsWith("半") ? 30
                : matcher.group(3) == null || matcher.group(3).isBlank() ? 0 : Integer.parseInt(matcher.group(3));
        if ("午後".equals(period) && hour < 12) hour += 12;
        if ("午前".equals(period) && hour == 12) hour = 0;
        return LocalTime.of(hour, minute);
    }

    private LocalTime defaultTime(String text) {
        if (text.contains("朝")) return LocalTime.of(8, 0);
        if (text.contains("昼") || text.contains("正午")) return LocalTime.of(12, 0);
        if (text.contains("夕方")) return LocalTime.of(18, 0);
        if (text.contains("夜")) return LocalTime.of(19, 0);
        if (text.contains("終日")) return LocalTime.MIDNIGHT;
        return LocalTime.of(9, 0);
    }

    private String removeDateTimeWords(String text) {
        String cleaned = text
                .replace("明々後日", "").replace("明後日", "").replace("明日", "").replace("今日", "")
                .replace("再来週", "").replace("来週", "").replace("今週", "")
                .replace("午前", "").replace("午後", "").replace("正午", "")
                .replace("朝", "").replace("昼", "").replace("夕方", "")
                .replace("夜", "").replace("終日", "")
                .replaceAll("[月火水木金土日]曜(?:日)?", "")
                .replaceFirst("^予定\\s*", "");
        cleaned = SLASH_DATE.matcher(cleaned).replaceAll("");
        cleaned = JP_DATE.matcher(cleaned).replaceAll("");
        cleaned = TIME.matcher(cleaned).replaceAll("");
        cleaned = AFTER_MINUTES.matcher(cleaned).replaceAll("");
        cleaned = AFTER_HOURS.matcher(cleaned).replaceAll("");
        cleaned = AFTER_DAYS.matcher(cleaned).replaceAll("");
        return cleaned.replaceFirst("^に", "").replaceAll("\\s+", " ").strip();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    public enum Type { SCHEDULE, SHOPPING, TASK }
    public record Interpretation(Type type, String command, String description) {}
}
