package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

@Service
public class NotificationPreferenceService {
    private final JdbcTemplate jdbc;

    public NotificationPreferenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        initialize();
    }

    private void initialize() {
        jdbc.execute("""
                alter table user_settings
                add column if not exists morning_notice_time time not null default '06:00',
                add column if not exists night_notice_time time not null default '21:00',
                add column if not exists quiet_start_time time not null default '23:00',
                add column if not exists quiet_end_time time not null default '07:00',
                add column if not exists quiet_hours_enabled boolean not null default false,
                add column if not exists notifications_paused_until date
                """);
    }

    public Preferences get(String userId) {
        ensure(userId);
        return jdbc.queryForObject("""
                select morning_notice_time, night_notice_time, quiet_start_time,
                       quiet_end_time, quiet_hours_enabled, notifications_paused_until
                from user_settings where line_user_id = ?
                """, (rs, rowNum) -> new Preferences(
                rs.getTime("morning_notice_time").toLocalTime(),
                rs.getTime("night_notice_time").toLocalTime(),
                rs.getTime("quiet_start_time").toLocalTime(),
                rs.getTime("quiet_end_time").toLocalTime(),
                rs.getBoolean("quiet_hours_enabled"),
                rs.getDate("notifications_paused_until") == null ? null
                        : rs.getDate("notifications_paused_until").toLocalDate()
        ), userId);
    }

    public Preferences setMorning(String userId, LocalTime time) {
        ensure(userId);
        jdbc.update("update user_settings set morning_notice_time = ? where line_user_id = ?", time, userId);
        return get(userId);
    }

    public Preferences setNight(String userId, LocalTime time) {
        ensure(userId);
        jdbc.update("update user_settings set night_notice_time = ? where line_user_id = ?", time, userId);
        return get(userId);
    }

    public Preferences setQuietHours(String userId, LocalTime start, LocalTime end) {
        ensure(userId);
        jdbc.update("""
                update user_settings
                set quiet_start_time = ?, quiet_end_time = ?, quiet_hours_enabled = true
                where line_user_id = ?
                """, start, end, userId);
        return get(userId);
    }

    public Preferences disableQuietHours(String userId) {
        ensure(userId);
        jdbc.update("update user_settings set quiet_hours_enabled = false where line_user_id = ?", userId);
        return get(userId);
    }

    public void pauseTomorrow(String userId) {
        ensure(userId);
        jdbc.update("update user_settings set notifications_paused_until = current_date + 1 where line_user_id = ?", userId);
    }

    public void resume(String userId) {
        ensure(userId);
        jdbc.update("update user_settings set notifications_paused_until = null where line_user_id = ?", userId);
    }

    public boolean isAllowedNow(String userId, LocalTime now) {
        Preferences p = get(userId);
        if (p.pausedUntil() != null && !java.time.LocalDate.now().isAfter(p.pausedUntil())) return false;
        return !p.quietEnabled() || !NotificationTimeParser.isQuiet(now, p.quietStart(), p.quietEnd());
    }

    private void ensure(String userId) {
        jdbc.update("insert into benly_users(line_user_id) values (?) on conflict (line_user_id) do nothing", userId);
        jdbc.update("insert into user_settings(line_user_id) values (?) on conflict (line_user_id) do nothing", userId);
    }

    public record Preferences(LocalTime morning, LocalTime night, LocalTime quietStart,
                              LocalTime quietEnd, boolean quietEnabled,
                              java.time.LocalDate pausedUntil) {}
}
