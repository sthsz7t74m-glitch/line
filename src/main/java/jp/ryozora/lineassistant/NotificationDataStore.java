package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.*;
import java.util.List;

@Repository
public class NotificationDataStore {
    private final JdbcTemplate jdbc;

    public NotificationDataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UpcomingSchedule> upcomingSchedules(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("""
                select s.id, s.line_user_id, s.title, s.starts_at, s.reminder_minutes
                from schedules s
                join user_settings u on u.line_user_id = s.line_user_id
                where u.schedule_notification = true
                  and s.starts_at >= ? and s.starts_at < ?
                order by s.starts_at
                """, (rs, rowNum) -> new UpcomingSchedule(
                rs.getLong("id"),
                rs.getString("line_user_id"),
                rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atOffset(ZoneOffset.ofHours(9)),
                rs.getString("reminder_minutes")
        ), Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    }

    public List<UpcomingTask> upcomingTasks(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("""
                select t.id, t.line_user_id, t.title, t.priority, t.due_at, t.reminder_minutes
                from tasks t
                join user_settings u on u.line_user_id = t.line_user_id
                where u.task_notification = true
                  and t.completed = false
                  and t.due_at is not null
                  and t.due_at >= ? and t.due_at < ?
                order by t.due_at
                """, (rs, rowNum) -> new UpcomingTask(
                rs.getLong("id"),
                rs.getString("line_user_id"),
                rs.getString("title"),
                rs.getString("priority"),
                rs.getTimestamp("due_at").toInstant().atOffset(ZoneOffset.ofHours(9)),
                rs.getString("reminder_minutes")
        ), Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    }

    public List<DueTask> dueTasks(LocalDate date, ZoneOffset offset) {
        OffsetDateTime start = date.atStartOfDay().atOffset(offset);
        OffsetDateTime end = start.plusDays(1);
        return jdbc.query("""
                select t.id, t.line_user_id, t.title, t.due_at
                from tasks t
                join user_settings u on u.line_user_id = t.line_user_id
                where u.task_notification = true
                  and t.completed = false
                  and t.due_at >= ? and t.due_at < ?
                order by t.due_at
                """, (rs, rowNum) -> new DueTask(
                rs.getLong("id"),
                rs.getString("line_user_id"),
                rs.getString("title"),
                rs.getTimestamp("due_at").toInstant().atOffset(offset)
        ), Timestamp.from(start.toInstant()), Timestamp.from(end.toInstant()));
    }

    public List<String> nightUsers(LocalDate today) {
        return jdbc.query("""
                select line_user_id from user_settings
                where night_notification = true
                  and (last_night_notice is null or last_night_notice <> ?)
                """, (rs, rowNum) -> rs.getString(1), today);
    }

    public NightSummary nightSummary(String userId, LocalDate date, ZoneOffset offset) {
        OffsetDateTime start = date.atStartOfDay().atOffset(offset);
        OffsetDateTime end = start.plusDays(1);
        Integer completed = jdbc.queryForObject("""
                select count(*) from tasks
                where line_user_id = ? and completed = true
                  and completed_at >= ? and completed_at < ?
                """, Integer.class, userId, Timestamp.from(start.toInstant()), Timestamp.from(end.toInstant()));
        Integer tomorrowSchedules = jdbc.queryForObject("""
                select count(*) from schedules
                where line_user_id = ? and starts_at >= ? and starts_at < ?
                """, Integer.class, userId,
                Timestamp.from(end.toInstant()), Timestamp.from(end.plusDays(1).toInstant()));
        Integer gained = jdbc.queryForObject("""
                select coalesce(sum(points), 0) from experience_logs
                where line_user_id = ? and created_at >= ? and created_at < ?
                """, Integer.class, userId, Timestamp.from(start.toInstant()), Timestamp.from(end.toInstant()));
        return new NightSummary(completed == null ? 0 : completed,
                tomorrowSchedules == null ? 0 : tomorrowSchedules,
                gained == null ? 0 : gained);
    }

    public boolean reserveDelivery(String userId, String type, String targetKey) {
        return jdbc.update("""
                insert into notification_delivery_log(line_user_id, notification_type, target_key)
                values (?, ?, ?)
                on conflict (line_user_id, notification_type, target_key) do nothing
                """, userId, type, targetKey) == 1;
    }

    public void markNight(String userId, LocalDate date) {
        jdbc.update("update user_settings set last_night_notice = ? where line_user_id = ?", date, userId);
    }

    public record UpcomingSchedule(long id, String userId, String title,
                                   OffsetDateTime startsAt, String reminderMinutes) {}
    public record UpcomingTask(long id, String userId, String title, String priority,
                               OffsetDateTime dueAt, String reminderMinutes) {}
    public record DueTask(long id, String userId, String title, OffsetDateTime dueAt) {}
    public record NightSummary(int completedTasks, int tomorrowSchedules, int gainedExperience) {}
}
