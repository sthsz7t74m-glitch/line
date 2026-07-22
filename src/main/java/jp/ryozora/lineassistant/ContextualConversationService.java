package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ContextualConversationService {
    private static final Duration TTL = Duration.ofMinutes(20);
    private static final Pattern NUMBER = Pattern.compile("(?:^|\\D)(\\d{1,4})番?");

    private final JdbcTemplate jdbc;
    private final TaskService taskService;
    private final BenlyCommandService commandService;

    public ContextualConversationService(JdbcTemplate jdbc, TaskService taskService,
                                         BenlyCommandService commandService) {
        this.jdbc = jdbc;
        this.taskService = taskService;
        this.commandService = commandService;
        initialize();
    }

    private void initialize() {
        // Use the SQL-standard type name so this works on both PostgreSQL and the H2 test database.
        jdbc.execute("""
                create table if not exists conversation_references (
                  line_user_id varchar(255) primary key,
                  domain varchar(32) not null,
                  selected_number integer,
                  pending_action varchar(32),
                  updated_at timestamp with time zone not null default current_timestamp
                )
                """);
    }

    /** Returns null when other command handlers should continue. */
    public String handle(String userId, String raw) {
        String text = normalize(raw);
        if (text.isBlank()) return null;

        if (isCancel(text)) {
            Reference ref = load(userId);
            if (ref == null || ref.pendingAction() == null) return null;
            save(userId, ref.domain(), ref.selectedNumber(), null);
            return "了解、操作は取り消したよ。";
        }

        if (looksLikeTaskView(text)) {
            save(userId, "TASK", null, null);
            if (text.contains("今週")) return taskService.handle(userId, "今週のタスク");
            if (text.contains("今日")) return taskService.handle(userId, "今日のタスク");
            if (text.contains("期限切れ") || text.contains("遅れ")) return taskService.handle(userId, "期限切れ");
            return taskService.handle(userId, "タスク一覧");
        }
        if (looksLikeShoppingView(text)) {
            save(userId, "SHOPPING", null, null);
            return commandService.handle(userId, "買い物一覧");
        }
        if (looksLikeMemoView(text)) {
            save(userId, "MEMO", null, null);
            return commandService.handle(userId, "メモ一覧");
        }

        Reference ref = load(userId);
        if (ref == null) return null;

        if (ref.pendingAction() != null && isConfirm(text)) {
            String response = execute(userId, ref.domain(), ref.selectedNumber(), ref.pendingAction());
            clear(userId);
            return response;
        }

        Integer number = extractNumber(text);
        if (number == null && containsReferenceWord(text)) number = ref.selectedNumber();
        if (number == null) return null;

        if (containsAny(text, "終わった", "完了", "できた", "済んだ")) {
            save(userId, ref.domain(), number, null);
            return execute(userId, ref.domain(), number, "COMPLETE");
        }
        if (containsAny(text, "買った", "購入", "買えた")) {
            save(userId, ref.domain(), number, null);
            return execute(userId, ref.domain(), number, "PURCHASE");
        }
        if (containsAny(text, "削除", "消して", "消したい")) {
            save(userId, ref.domain(), number, "DELETE");
            return number + "番を削除していい？\n『はい』で実行、『やめる』で取り消すよ。";
        }
        return null;
    }

    private String execute(String userId, String domain, Integer number, String action) {
        if (number == null) return "対象の番号が分からなかったよ。もう一度一覧を開いて番号を指定してね。";
        return switch (domain + ":" + action) {
            case "TASK:COMPLETE" -> taskService.handle(userId, "完了 " + number);
            case "TASK:DELETE" -> taskService.handle(userId, "タスク削除 " + number);
            case "SHOPPING:PURCHASE" -> commandService.handle(userId, "購入 " + number);
            case "MEMO:DELETE" -> commandService.handle(userId, "メモ削除 " + number);
            default -> "その操作にはまだ対応していないよ。";
        };
    }

    private Reference load(String userId) {
        var rows = jdbc.query("""
                select domain, selected_number, pending_action, updated_at
                from conversation_references where line_user_id = ?
                """, (rs, rowNum) -> new Reference(
                rs.getString("domain"),
                (Integer) rs.getObject("selected_number"),
                rs.getString("pending_action"),
                rs.getObject("updated_at", OffsetDateTime.class)
        ), userId);
        if (rows.isEmpty()) return null;
        Reference ref = rows.get(0);
        if (ref.updatedAt().isBefore(OffsetDateTime.now().minus(TTL))) {
            clear(userId);
            return null;
        }
        return ref;
    }

    private void save(String userId, String domain, Integer number, String pendingAction) {
        jdbc.update("""
                merge into conversation_references key(line_user_id)
                values (?, ?, ?, ?, current_timestamp)
                """, userId, domain, number, pendingAction);
    }

    private void clear(String userId) {
        jdbc.update("delete from conversation_references where line_user_id = ?", userId);
    }

    private Integer extractNumber(String text) {
        Matcher matcher = NUMBER.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private boolean looksLikeTaskView(String text) {
        return containsAny(text, "タスク見せて", "タスク見たい", "やること見せて", "やることある", "今日のタスク見たい", "今週のタスク見たい");
    }

    private boolean looksLikeShoppingView(String text) {
        return containsAny(text, "買い物見せて", "買うもの見せて", "買い物リスト見たい", "買うものある");
    }

    private boolean looksLikeMemoView(String text) {
        return containsAny(text, "メモ見せて", "メモ見たい", "メモある");
    }

    private boolean containsReferenceWord(String text) {
        return containsAny(text, "それ", "さっきの", "そのやつ", "最後のやつ");
    }

    private boolean isConfirm(String text) {
        return text.equals("はい") || text.equals("お願い") || text.equals("実行") || text.equals("消して");
    }

    private boolean isCancel(String text) {
        return text.equals("やめる") || text.equals("やっぱやめる") || text.equals("キャンセル");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.replace('\u3000', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record Reference(String domain, Integer selectedNumber, String pendingAction,
                             OffsetDateTime updatedAt) {}
}
