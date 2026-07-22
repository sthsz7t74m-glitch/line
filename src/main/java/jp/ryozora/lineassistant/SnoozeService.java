package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SnoozeService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Pattern COMMAND = Pattern.compile("^スヌーズ\\s+(10|30|明日)\\s+(\\d+)$");
    private final JdbcTemplate jdbc;

    public SnoozeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean supports(String raw) {
        return raw != null && COMMAND.matcher(raw.strip()).matches();
    }

    @Transactional
    public String handle(String userId, String raw) {
        Matcher matcher = COMMAND.matcher(raw.strip());
        if (!matcher.matches()) return null;
        long scheduleId = Long.parseLong(matcher.group(2));
        Source source = jdbc.query("""
                select title, starts_at from schedules
                where id=? and line_user_id=?
                """, rs -> rs.next() ? new Source(
                rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atZone(TOKYO).toLocalDateTime()) : null,
                scheduleId, userId);
        if (source == null) return "元の予定が見つからなかったよ。";

        LocalDateTime now = LocalDateTime.now(TOKYO).withSecond(0).withNano(0);
        LocalDateTime next;
        String selected = matcher.group(1);
        if (selected.equals("明日")) {
            next = LocalDateTime.of(LocalDate.now(TOKYO).plusDays(1), source.startsAt().toLocalTime());
        } else {
            next = now.plusMinutes(Integer.parseInt(selected));
        }

        jdbc.update("""
                insert into schedules(line_user_id,title,starts_at,reminder_minutes)
                values (?,?,?,?)
                """, userId, source.title() + "（再通知）",
                Timestamp.from(next.atZone(TOKYO).toInstant()), "0");

        return "再通知を設定したよ。\n"
                + next.format(DateTimeFormatter.ofPattern("M月d日 H:mm"))
                + "　" + source.title();
    }

    private record Source(String title, LocalDateTime startsAt) {}
}
