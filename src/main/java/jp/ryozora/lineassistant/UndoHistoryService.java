package jp.ryozora.lineassistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UndoHistoryService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public UndoHistoryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Long capture(String userId, PendingInputContext.Type type, int number) {
        Snapshot snapshot = snapshot(userId, type, number);
        if (snapshot == null) return null;
        try {
            return jdbc.queryForObject("""
                    insert into undo_history(line_user_id,entity_type,entity_id,action_type,snapshot_json,description)
                    values (?,?,?,?,?,?) returning id
                    """, Long.class, userId, snapshot.entityType(), snapshot.entityId(), "EDIT",
                    mapper.writeValueAsString(snapshot.values()), snapshot.description());
        } catch (Exception e) {
            return null;
        }
    }

    public void discard(Long historyId) {
        if (historyId != null) jdbc.update("delete from undo_history where id=? and undone=false", historyId);
    }

    @Transactional
    public String undoLatest(String userId) {
        List<UndoRow> rows = jdbc.query("""
                select id,entity_type,entity_id,snapshot_json,description
                from undo_history
                where line_user_id=? and undone=false and created_at>=current_timestamp - interval '30 minutes'
                order by created_at desc,id desc limit 1
                """, (rs, i) -> new UndoRow(rs.getLong("id"), rs.getString("entity_type"),
                rs.getLong("entity_id"), rs.getString("snapshot_json"), rs.getString("description")), userId);
        if (rows.isEmpty()) return "元に戻せる直近の変更はないよ。";

        UndoRow row = rows.get(0);
        try {
            Map<String, Object> values = mapper.readValue(row.snapshotJson(), MAP_TYPE);
            int changed = restore(userId, row.entityType(), row.entityId(), values);
            if (changed != 1) return "元に戻す対象が見つからなかったよ。";
            jdbc.update("update undo_history set undone=true,undone_at=current_timestamp where id=?", row.id());
            return "✓ 元に戻したよ。\n" + (row.description() == null ? "直前の変更を復元したよ。" : row.description());
        } catch (Exception e) {
            return "元に戻せなかったよ。対象が変更・削除されている可能性があるよ。";
        }
    }

    private Snapshot snapshot(String userId, PendingInputContext.Type type, int number) {
        return switch (type) {
            case MEMO_EDIT -> one("""
                    select id,content,favorite,tags,archived from memos
                    where line_user_id=? and archived=false
                    order by favorite desc,created_at desc limit 1 offset ?
                    """, userId, number, "MEMO", "メモの編集を取り消したよ");
            case TASK_EDIT, TASK_POSTPONE -> one("""
                    select id,title,priority,due_at,reminder_minutes,completed from tasks
                    where line_user_id=? and completed=false
                    order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                             due_at nulls last,created_at limit 1 offset ?
                    """, userId, number, "TASK", "タスクの変更を取り消したよ");
            case SCHEDULE_EDIT -> one("""
                    select id,title,starts_at,reminder_minutes,series_id,recurrence_label from schedules
                    where line_user_id=? and starts_at>=current_timestamp
                    order by starts_at limit 1 offset ?
                    """, userId, number, "SCHEDULE", "予定の変更を取り消したよ");
            case HABIT_EDIT -> one("""
                    select id,name,active_days,reminder_time,active from habits
                    where line_user_id=? order by active desc,id limit 1 offset ?
                    """, userId, number, "HABIT", "習慣の編集を取り消したよ");
            case EXPENSE_EDIT -> one("""
                    select id,amount,description,category,spent_on from expenses
                    where line_user_id=? order by spent_on desc,id desc limit 1 offset ?
                    """, userId, number, "EXPENSE", "支出の編集を取り消したよ");
        };
    }

    private Snapshot one(String sql, String userId, int number, String entityType, String description) {
        if (number < 1) return null;
        List<Snapshot> rows = jdbc.query(sql, rs -> {
            if (!rs.next()) return List.of();
            Map<String, Object> values = new LinkedHashMap<>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String name = meta.getColumnLabel(i);
                if (!"id".equals(name)) values.put(name, serializable(rs.getObject(i)));
            }
            return List.of(new Snapshot(entityType, rs.getLong("id"), values, description));
        }, userId, number - 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Object serializable(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof Time time) return time.toLocalTime().toString();
        return value;
    }

    private int restore(String userId, String type, long id, Map<String, Object> v) {
        return switch (type) {
            case "MEMO" -> jdbc.update("""
                    update memos set content=?,favorite=?,tags=?,archived=? where id=? and line_user_id=?
                    """, v.get("content"), v.get("favorite"), v.get("tags"), v.get("archived"), id, userId);
            case "TASK" -> jdbc.update("""
                    update tasks set title=?,priority=?,due_at=?,reminder_minutes=?,completed=?
                    where id=? and line_user_id=?
                    """, v.get("title"), v.get("priority"), timestamp(v.get("due_at")),
                    v.get("reminder_minutes"), v.get("completed"), id, userId);
            case "SCHEDULE" -> jdbc.update("""
                    update schedules set title=?,starts_at=?,reminder_minutes=?,series_id=?,recurrence_label=?
                    where id=? and line_user_id=?
                    """, v.get("title"), timestamp(v.get("starts_at")), v.get("reminder_minutes"),
                    v.get("series_id"), v.get("recurrence_label"), id, userId);
            case "HABIT" -> jdbc.update("""
                    update habits set name=?,active_days=?,reminder_time=?,active=?,updated_at=current_timestamp
                    where id=? and line_user_id=?
                    """, v.get("name"), v.get("active_days"), time(v.get("reminder_time")), v.get("active"), id, userId);
            case "EXPENSE" -> jdbc.update("""
                    update expenses set amount=?,description=?,category=?,spent_on=?,updated_at=current_timestamp
                    where id=? and line_user_id=?
                    """, v.get("amount"), v.get("description"), v.get("category"), date(v.get("spent_on")), id, userId);
            default -> 0;
        };
    }

    private Timestamp timestamp(Object value) {
        if (value == null) return null;
        return Timestamp.from(Instant.parse(value.toString()));
    }

    private Time time(Object value) {
        return value == null ? null : Time.valueOf(value.toString());
    }

    private Date date(Object value) {
        return value == null ? null : Date.valueOf(value.toString());
    }

    private record Snapshot(String entityType, long entityId, Map<String, Object> values, String description) {}
    private record UndoRow(long id, String entityType, long entityId, String snapshotJson, String description) {}
}
