package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class BenlyStore {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final JdbcTemplate jdbc;

    public BenlyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureUser(String userId) {
        jdbc.update("""
                insert into benly_users(line_user_id)
                values (?)
                on conflict (line_user_id) do nothing
                """, userId);
        jdbc.update("""
                insert into user_settings(line_user_id)
                values (?)
                on conflict (line_user_id) do nothing
                """, userId);
    }

    public long addMemo(String userId, String content) {
        ensureUser(userId);
        Long id = jdbc.queryForObject("""
                insert into memos(line_user_id, content)
                values (?, ?)
                returning id
                """, Long.class, userId, content);
        return id == null ? -1 : id;
    }

    public List<Item> listMemos(String userId) {
        ensureUser(userId);
        return memoQuery("""
                select id, content, favorite, tags from memos
                where line_user_id = ? and archived = false
                order by favorite desc, created_at desc limit 30
                """, userId);
    }

    public List<Item> searchMemos(String userId, String keyword) {
        ensureUser(userId);
        String pattern = "%" + keyword.toLowerCase() + "%";
        return memoQuery("""
                select id, content, favorite, tags from memos
                where line_user_id = ? and archived = false
                  and (lower(content) like ? or lower(coalesce(tags, '')) like ?)
                order by favorite desc, created_at desc limit 30
                """, userId, pattern, pattern);
    }

    private List<Item> memoQuery(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> {
            StringBuilder text = new StringBuilder();
            if (rs.getBoolean("favorite")) text.append("★ ");
            text.append(rs.getString("content"));
            String tags = rs.getString("tags");
            if (tags != null && !tags.isBlank()) text.append("\n  #").append(tags.replace(",", " #"));
            return new Item(rs.getLong("id"), text.toString());
        }, args);
    }

    public boolean deleteMemo(String userId, long id) {
        return jdbc.update("delete from memos where id = ? and line_user_id = ?", id, userId) == 1;
    }

    public boolean editMemo(String userId, long id, String content) {
        return jdbc.update("""
                update memos set content = ?, updated_at = current_timestamp
                where id = ? and line_user_id = ?
                """, content, id, userId) == 1;
    }

    public Boolean toggleMemoFavorite(String userId, long id) {
        return jdbc.query("""
                update memos set favorite = not favorite, updated_at = current_timestamp
                where id = ? and line_user_id = ?
                returning favorite
                """, rs -> rs.next() ? rs.getBoolean(1) : null, id, userId);
    }

    public boolean setMemoTags(String userId, long id, String tags) {
        return jdbc.update("""
                update memos set tags = ?, updated_at = current_timestamp
                where id = ? and line_user_id = ?
                """, tags, id, userId) == 1;
    }

    public int clearMemos(String userId) {
        return jdbc.update("delete from memos where line_user_id = ?", userId);
    }

    public long addTask(String userId, String title) {
        ensureUser(userId);
        Long id = jdbc.queryForObject("""
                insert into tasks(line_user_id, title)
                values (?, ?)
                returning id
                """, Long.class, userId, title);
        return id == null ? -1 : id;
    }

    public List<Item> listTasks(String userId) {
        ensureUser(userId);
        return jdbc.query("""
                select id, title, priority, due_at from tasks
                where line_user_id = ? and completed = false
                order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                         due_at nulls last, created_at
                limit 30
                """, (rs, rowNum) -> {
            String priority = rs.getString("priority");
            StringBuilder text = new StringBuilder();
            if ("HIGH".equals(priority)) text.append("[高] ");
            else if ("LOW".equals(priority)) text.append("[低] ");
            else text.append("[中] ");
            text.append(rs.getString("title"));
            Timestamp due = rs.getTimestamp("due_at");
            if (due != null) {
                LocalDateTime local = due.toInstant().atZone(TOKYO).toLocalDateTime();
                text.append("（期限 ").append(local.format(DateTimeFormatter.ofPattern("M/d H:mm")));
                if (local.isBefore(LocalDateTime.now(TOKYO))) text.append("・期限切れ");
                text.append("）");
            }
            return new Item(rs.getLong("id"), text.toString());
        }, userId);
    }

    @Transactional
    public boolean completeTask(String userId, long id) {
        int changed = jdbc.update("""
                update tasks set completed = true, completed_at = current_timestamp
                where id = ? and line_user_id = ? and completed = false
                """, id, userId);
        if (changed == 1) {
            addExperience(userId, 10, "TASK_COMPLETED");
            return true;
        }
        return false;
    }

    public long addShoppingItem(String userId, String name) {
        ensureUser(userId);
        Long id = jdbc.queryForObject("""
                insert into shopping_items(line_user_id, name)
                values (?, ?)
                returning id
                """, Long.class, userId, name);
        return id == null ? -1 : id;
    }

    public List<Item> listShoppingItems(String userId) {
        ensureUser(userId);
        return jdbc.query("""
                select id, name from shopping_items
                where line_user_id = ? and purchased = false
                order by created_at
                limit 50
                """, (rs, rowNum) -> new Item(rs.getLong("id"), rs.getString("name")), userId);
    }

    @Transactional
    public boolean purchaseShoppingItem(String userId, long id) {
        int changed = jdbc.update("""
                update shopping_items set purchased = true, purchased_at = current_timestamp
                where id = ? and line_user_id = ? and purchased = false
                """, id, userId);
        if (changed == 1) {
            addExperience(userId, 5, "SHOPPING_COMPLETED");
            return true;
        }
        return false;
    }

    public long addSchedule(String userId, String title, OffsetDateTime startsAt) {
        ensureUser(userId);
        Long id = jdbc.queryForObject("""
                insert into schedules(line_user_id, title, starts_at)
                values (?, ?, ?)
                returning id
                """, Long.class, userId, title, Timestamp.from(startsAt.toInstant()));
        return id == null ? -1 : id;
    }

    public List<ScheduleItem> listTodaySchedules(String userId, ZoneOffset offset) {
        ensureUser(userId);
        OffsetDateTime now = OffsetDateTime.now(offset);
        OffsetDateTime start = now.toLocalDate().atStartOfDay().atOffset(offset);
        OffsetDateTime end = start.plusDays(1);
        return jdbc.query("""
                select id, title, starts_at from schedules
                where line_user_id = ? and starts_at >= ? and starts_at < ?
                order by starts_at
                """, (rs, rowNum) -> new ScheduleItem(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atOffset(offset)
        ), userId, Timestamp.from(start.toInstant()), Timestamp.from(end.toInstant()));
    }

    public int experience(String userId) {
        ensureUser(userId);
        Integer value = jdbc.queryForObject(
                "select experience from benly_users where line_user_id = ?",
                Integer.class,
                userId
        );
        return value == null ? 0 : value;
    }

    public DataSummary dataSummary(String userId) {
        ensureUser(userId);
        return new DataSummary(
                count("memos", userId),
                count("tasks", userId),
                count("shopping_items", userId),
                count("expenses", userId),
                count("schedules", userId),
                count("experience_logs", userId)
        );
    }

    private int count(String table, String userId) {
        // table name is selected only from hard-coded constants in this class; user input is never used as SQL syntax.
        Integer value = jdbc.queryForObject("select count(*) from " + table + " where line_user_id = ?", Integer.class, userId);
        return value == null ? 0 : value;
    }

    @Transactional
    public void deleteAllUserData(String userId) {
        // Every value is bound as a parameter. No user-provided text is concatenated into SQL.
        jdbc.update("delete from daily_mission_rewards where line_user_id = ?", userId);
        jdbc.update("delete from experience_logs where line_user_id = ?", userId);
        jdbc.update("delete from expenses where line_user_id = ?", userId);
        jdbc.update("delete from schedules where line_user_id = ?", userId);
        jdbc.update("delete from shopping_items where line_user_id = ?", userId);
        jdbc.update("delete from tasks where line_user_id = ?", userId);
        jdbc.update("delete from memos where line_user_id = ?", userId);
        jdbc.update("delete from locations where line_user_id = ?", userId);
        jdbc.update("delete from user_settings where line_user_id = ?", userId);
        jdbc.update("delete from benly_users where line_user_id = ?", userId);
    }

    private void addExperience(String userId, int points, String reason) {
        ensureUser(userId);
        jdbc.update("update benly_users set experience = experience + ?, updated_at = current_timestamp where line_user_id = ?",
                points, userId);
        jdbc.update("insert into experience_logs(line_user_id, points, reason) values (?, ?, ?)",
                userId, points, reason);
    }

    public record Item(long id, String text) {}
    public record ScheduleItem(long id, String title, OffsetDateTime startsAt) {}
    public record DataSummary(int memos, int tasks, int shoppingItems, int expenses,
                              int schedules, int experienceLogs) {}
}
