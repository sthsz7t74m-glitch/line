package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NaturalLanguageService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter SCHEDULE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])(?:時|:)([0-5]?\\d)?(?:分)?");

    public Interpretation interpret(String text) {
        if (text == null || text.isBlank()) return null;

        String normalized = text.strip();
        LocalDate date = extractDate(normalized);
        LocalTime time = extractTime(normalized);

        if (date != null || time != null) {
            LocalDate resolvedDate = date != null ? date : LocalDate.now(TOKYO);
            LocalTime resolvedTime = time != null ? time : LocalTime.of(9, 0);
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
                    .replace("買って", "")
                    .replace("買う", "")
                    .replace("切れた", "")
                    .replace("なくなった", "")
                    .replace("補充", "")
                    .replace("購入", "")
                    .replace("を", "")
                    .strip();
            if (!item.isBlank()) {
                return new Interpretation(Type.SHOPPING, "買い物 " + item, "買い物リストに入れるで！");
            }
        }

        if (containsAny(normalized, "忘れない", "忘れそう", "やる", "しなきゃ", "すること", "あとで")) {
            String task = normalized
                    .replace("忘れないように", "")
                    .replace("忘れない", "")
                    .replace("忘れそう", "")
                    .replace("しなきゃ", "")
                    .replace("すること", "")
                    .strip();
            if (!task.isBlank()) {
                return new Interpretation(Type.TASK, "タスク " + task, "タスクとして登録するで！");
            }
        }

        return null;
    }

    private LocalDate extractDate(String text) {
        LocalDate today = LocalDate.now(TOKYO);
        if (text.contains("明後日")) return today.plusDays(2);
        if (text.contains("明日")) return today.plusDays(1);
        if (text.contains("今日")) return today;

        String[] names = {"月曜", "火曜", "水曜", "木曜", "金曜", "土曜", "日曜"};
        DayOfWeek[] days = {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY};
        for (int i = 0; i < names.length; i++) {
            if (text.contains(names[i])) {
                int delta = (days[i].getValue() - today.getDayOfWeek().getValue() + 7) % 7;
                if (text.contains("来週")) delta = delta == 0 ? 7 : delta + 7;
                else if (delta == 0) delta = 7;
                return today.plusDays(delta);
            }
        }
        return null;
    }

    private LocalTime extractTime(String text) {
        Matcher matcher = TIME.matcher(text);
        if (!matcher.find()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null || matcher.group(2).isBlank()
                ? 0 : Integer.parseInt(matcher.group(2));
        return LocalTime.of(hour, minute);
    }

    private String removeDateTimeWords(String text) {
        String cleaned = text
                .replace("明後日", "")
                .replace("明日", "")
                .replace("今日", "")
                .replace("来週", "")
                .replaceAll("[月火水木金土日]曜(?:日)?", "");
        return TIME.matcher(cleaned).replaceAll("")
                .replace("に", " ")
                .replaceAll("\\s+", " ");
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    public enum Type { SCHEDULE, SHOPPING, TASK }

    public record Interpretation(Type type, String command, String description) {}
}
