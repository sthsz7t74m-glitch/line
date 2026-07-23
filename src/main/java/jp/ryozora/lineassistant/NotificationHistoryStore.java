package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class NotificationHistoryStore {
    private static final int LIMIT = 20;
    private static final ZoneOffset TOKYO = ZoneOffset.ofHours(9);

    private final JdbcTemplate jdbc;

    public NotificationHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(String userId, String type, String summary) {
        if (userId == null || userId.isBlank() || summary == null || summary.isBlank()) return;
        String safeType = type == null || type.isBlank() ? "通知" : truncate(type, 80);
        String safeSummary = truncate(summary, 500);
        jdbc.update("""
                insert into notification_history(line_user_id,notification_type,summary,delivery_status,sent_at)
                values (?,?,?,?,current_timestamp)
                """, userId, safeType, safeSummary, "SENT");
        jdbc.update("delete from notification_history where sent_at < ?",
                Timestamp.from(Instant.now().minus(90, ChronoUnit.DAYS)));
    }

    public List<Entry> list(String userId) {
        if (userId == null || userId.isBlank()) return List.of();
        return jdbc.query("""
                select notification_type,summary,sent_at
                from notification_history
                where line_user_id=?
                order by sent_at desc,id desc
                limit ?
                """, (rs, i) -> {
            Timestamp sent = rs.getTimestamp("sent_at");
            OffsetDateTime at = sent == null
                    ? OffsetDateTime.now(TOKYO)
                    : sent.toInstant().atOffset(TOKYO);
            return new Entry(rs.getString("notification_type"), rs.getString("summary"), at);
        }, userId, LIMIT);
    }

    public int count(String userId) {
        if (userId == null || userId.isBlank()) return 0;
        Integer value = jdbc.queryForObject(
                "select count(*) from notification_history where line_user_id=?",
                Integer.class, userId);
        return value == null ? 0 : value;
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Entry(String type, String summary, OffsetDateTime sentAt) { }
}
