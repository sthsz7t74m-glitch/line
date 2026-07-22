package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class NotificationStore {
    private final JdbcTemplate jdbc;

    public NotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Settings get(String userId) {
        ensure(userId);
        return jdbc.queryForObject("""
                select morning_notification, rain_notification, schedule_notification,
                       task_notification, night_notification, weather_latitude,
                       weather_longitude, weather_area
                from user_settings where line_user_id = ?
                """, (rs, rowNum) -> new Settings(
                rs.getBoolean("morning_notification"),
                rs.getBoolean("rain_notification"),
                rs.getBoolean("schedule_notification"),
                rs.getBoolean("task_notification"),
                rs.getBoolean("night_notification"),
                rs.getDouble("weather_latitude"),
                rs.getDouble("weather_longitude"),
                rs.getString("weather_area")
        ), userId);
    }

    public Settings toggle(String userId, String type) {
        ensure(userId);
        String column = switch (type) {
            case "朝" -> "morning_notification";
            case "雨" -> "rain_notification";
            case "予定" -> "schedule_notification";
            case "タスク" -> "task_notification";
            case "夜" -> "night_notification";
            default -> throw new IllegalArgumentException("Unsupported notification type");
        };
        // column is selected only from the fixed allow-list above; user input is never inserted directly.
        jdbc.update("update user_settings set " + column + " = not " + column + " where line_user_id = ?", userId);
        return get(userId);
    }

    public List<String> morningUsers(LocalDate today) {
        return jdbc.query("""
                select line_user_id from user_settings
                where morning_notification = true
                  and (last_morning_notice is null or last_morning_notice <> ?)
                """, (rs, rowNum) -> rs.getString(1), today);
    }

    public void markMorning(String userId, LocalDate date) {
        jdbc.update("update user_settings set last_morning_notice = ? where line_user_id = ?", date, userId);
    }

    public List<String> rainUsers(LocalDateTime threshold) {
        return jdbc.query("""
                select line_user_id from user_settings
                where rain_notification = true
                  and (last_rain_notice_at is null or last_rain_notice_at < ?)
                """, (rs, rowNum) -> rs.getString(1), threshold);
    }

    public void markRain(String userId, LocalDateTime at) {
        jdbc.update("update user_settings set last_rain_notice_at = ? where line_user_id = ?", at, userId);
    }

    private void ensure(String userId) {
        jdbc.update("insert into benly_users(line_user_id) values (?) on conflict (line_user_id) do nothing", userId);
        jdbc.update("insert into user_settings(line_user_id) values (?) on conflict (line_user_id) do nothing", userId);
    }

    public record Settings(boolean morning, boolean rain, boolean schedule, boolean task,
                           boolean night, double latitude, double longitude, String area) {}
}
