package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Service
public class NotificationPreferenceCommandService {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("H:mm");
    private final NotificationPreferenceService preferences;

    public NotificationPreferenceCommandService(NotificationPreferenceService preferences) {
        this.preferences = preferences;
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        return text.startsWith("朝通知 ") || text.startsWith("夜通知 ")
                || text.startsWith("通知しない時間 ") || text.startsWith("静音時間 ")
                || text.equals("静音時間オフ") || text.equals("通知一時停止")
                || text.equals("明日だけ通知休み") || text.equals("通知再開")
                || text.equals("通知時刻") || text.equals("通知時間") || text.equals("通知設定確認");
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        try {
            if (text.startsWith("朝通知 ")) {
                LocalTime time = NotificationTimeParser.parseTime(text.substring("朝通知 ".length()));
                return summary("朝のお知らせを " + FORMAT.format(time) + " に変更したよ。",
                        preferences.setMorning(userId, time));
            }
            if (text.startsWith("夜通知 ")) {
                LocalTime time = NotificationTimeParser.parseTime(text.substring("夜通知 ".length()));
                return summary("夜のまとめを " + FORMAT.format(time) + " に変更したよ。",
                        preferences.setNight(userId, time));
            }
            if (text.startsWith("通知しない時間 ") || text.startsWith("静音時間 ")) {
                String value = text.substring(text.indexOf(' ') + 1);
                NotificationTimeParser.QuietHours quiet = NotificationTimeParser.parseQuietHours(value);
                return summary("静音時間を設定したよ。", preferences.setQuietHours(userId, quiet.start(), quiet.end()));
            }
            if (text.equals("静音時間オフ")) {
                return summary("静音時間をオフにしたよ。", preferences.disableQuietHours(userId));
            }
            if (text.equals("通知一時停止") || text.equals("明日だけ通知休み")) {
                preferences.pauseTomorrow(userId);
                return "明日いっぱいまで通知を一時停止したよ。\n『通知再開』でいつでも戻せるよ。";
            }
            if (text.equals("通知再開")) {
                preferences.resume(userId);
                return "通知を再開したよ。";
            }
            return summary("現在の通知時刻だよ。", preferences.get(userId));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private String summary(String heading, NotificationPreferenceService.Preferences p) {
        String quiet = p.quietEnabled()
                ? FORMAT.format(p.quietStart()) + "〜" + FORMAT.format(p.quietEnd())
                : "オフ";
        String paused = p.pausedUntil() == null ? "なし" : p.pausedUntil() + "まで";
        return heading + "\n\n"
                + "朝通知：" + FORMAT.format(p.morning()) + "\n"
                + "夜通知：" + FORMAT.format(p.night()) + "\n"
                + "静音時間：" + quiet + "\n"
                + "一時停止：" + paused;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.replace('\u3000', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }
}
