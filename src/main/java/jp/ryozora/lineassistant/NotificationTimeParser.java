package jp.ryozora.lineassistant;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NotificationTimeParser {
    private static final Pattern TIME = Pattern.compile("(?:^|\\s)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?:$|\\s)");
    private static final Pattern QUIET = Pattern.compile("([01]?\\d|2[0-3])[:：]([0-5]\\d)\\s*[〜～~-]\\s*([01]?\\d|2[0-3])[:：]([0-5]\\d)");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("H:mm");

    private NotificationTimeParser() {}

    public static LocalTime parseTime(String text) {
        Matcher matcher = TIME.matcher(normalize(text));
        if (!matcher.find()) throw new IllegalArgumentException("時刻は 7:30 の形式で指定してね。");
        return LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    public static QuietHours parseQuietHours(String text) {
        Matcher matcher = QUIET.matcher(normalize(text));
        if (!matcher.find()) throw new IllegalArgumentException("静音時間は 23:00〜7:00 の形式で指定してね。");
        return new QuietHours(
                LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))),
                LocalTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)))
        );
    }

    public static boolean isQuiet(LocalTime now, LocalTime start, LocalTime end) {
        if (start.equals(end)) return false;
        if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
        return !now.isBefore(start) || now.isBefore(end);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace('\u3000', ' ').strip();
    }

    public record QuietHours(LocalTime start, LocalTime end) {}
}
