package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ConversationalCommandService {
    private static final Duration CONTEXT_TTL = Duration.ofMinutes(20);

    private static final List<String> ADD_PHRASES = List.of(
            "追加してほしい", "登録してほしい", "記録してほしい", "入れてほしい",
            "追加したい", "登録したい", "記録したい", "入れたい", "作りたい", "つくりたい",
            "残したい", "取りたい", "追加して", "登録して", "記録して", "入れて",
            "作って", "つくって", "つけたい", "メモしたい"
    );
    private static final List<String> VIEW_PHRASES = List.of(
            "見せてほしい", "教えてほしい", "確認したい", "見たい", "見せて", "教えて",
            "確認", "一覧", "どんな", "何がある", "なにがある", "ある？", "ある?"
    );
    private static final List<String> DELETE_PHRASES = List.of("削除したい", "消したい", "削除して", "消して");
    private static final List<String> EDIT_PHRASES = List.of("変更したい", "編集したい", "直したい", "変更して", "編集して");
    private static final List<String> COMPLETE_PHRASES = List.of("完了したい", "終わった", "終えた", "済んだ", "できた");

    private final JdbcTemplate jdbc;
    private final BenlyStore store;
    private final TaskService taskService;
    private final BenlyCommandService commandService;
    private final AdvancedScheduleService scheduleService;
    private final NaturalLanguageService naturalLanguageService;
    private final ExpenseService expenseService;
    private final HabitService habitService;
    private final WeatherCommandService weatherService;
    private final DailyProgressService progressService;
    private final RpgService rpgService;

    public ConversationalCommandService(JdbcTemplate jdbc,
                                        BenlyStore store,
                                        TaskService taskService,
                                        BenlyCommandService commandService,
                                        AdvancedScheduleService scheduleService,
                                        NaturalLanguageService naturalLanguageService,
                                        ExpenseService expenseService,
                                        HabitService habitService,
                                        WeatherCommandService weatherService,
                                        DailyProgressService progressService,
                                        RpgService rpgService) {
        this.jdbc = jdbc;
        this.store = store;
        this.taskService = taskService;
        this.commandService = commandService;
        this.scheduleService = scheduleService;
        this.naturalLanguageService = naturalLanguageService;
        this.expenseService = expenseService;
        this.habitService = habitService;
        this.weatherService = weatherService;
        this.progressService = progressService;
        this.rpgService = rpgService;
    }

    /** Returns null when the ordinary command routes should continue processing the message. */
    public String handle(String userId, String raw) {
        String text = normalize(raw);
        if (text.isBlank()) return null;

        if (text.equals("会話ヘルプ") || text.equals("自然会話ヘルプ") || text.equals("会話モード")) {
            return help();
        }

        PendingAction pending = loadPending(userId);
        if (isCancel(text)) {
            if (pending == null) return null;
            clearPending(userId);
            return "了解、さっきの追加はやめておくね。";
        }

        Intent intent = detectIntent(text);
        if (intent != null) return handleIntent(userId, text, intent);

        if (isCanonicalCommand(text)) {
            if (pending != null) clearPending(userId);
            return null;
        }

        if (pending != null) return consumePending(userId, pending, text);
        return null;
    }

    private String handleIntent(String userId, String text, Intent intent) {
        clearPending(userId);
        return switch (intent.action()) {
            case ADD -> {
                String detail = extractDetail(text, intent.domain(), ADD_PHRASES);
                if (detail.isBlank()) {
                    savePending(userId, pendingFor(intent.domain()));
                    yield addPrompt(intent.domain());
                }
                yield executeAdd(userId, intent.domain(), detail, false);
            }
            case VIEW -> executeView(userId, intent.domain(), text);
            case DELETE -> deleteGuide(intent.domain());
            case EDIT -> editGuide(intent.domain());
            case COMPLETE -> completeGuide(intent.domain());
        };
    }

    private String consumePending(String userId, PendingAction pending, String text) {
        Domain domain = switch (pending) {
            case TASK_ADD -> Domain.TASK;
            case SCHEDULE_ADD -> Domain.SCHEDULE;
            case MEMO_ADD -> Domain.MEMO;
            case SHOPPING_ADD -> Domain.SHOPPING;
            case EXPENSE_ADD -> Domain.EXPENSE;
            case HABIT_ADD -> Domain.HABIT;
        };
        return executeAdd(userId, domain, text, true);
    }

    private String executeAdd(String userId, Domain domain, String detail, boolean fromPending) {
        String value = cleanDetail(detail);
        if (value.isBlank()) {
            if (!fromPending) savePending(userId, pendingFor(domain));
            return addPrompt(domain);
        }

        String response;
        switch (domain) {
            case TASK -> {
                String command = taskService.supports(value) ? value : "タスク " + value;
                response = taskService.handle(userId, command);
            }
            case SCHEDULE -> {
                response = addSchedule(userId, value);
                if (response == null) {
                    savePending(userId, PendingAction.SCHEDULE_ADD);
                    return "日時と内容をもう少し詳しく送ってね。\n例：明日19時 歯医者";
                }
            }
            case MEMO -> response = commandService.handle(userId, "メモ " + value);
            case SHOPPING -> response = commandService.handle(userId, "買い物 " + value);
            case EXPENSE -> {
                String command = expenseService.supports(value) ? value : "支出 " + value;
                response = expenseService.handle(userId, command);
                if (response.startsWith("金額を読み取れなかった")) {
                    savePending(userId, PendingAction.EXPENSE_ADD);
                    return response + "\n金額を含めてもう一度送ってね。";
                }
            }
            case HABIT -> response = habitService.handle(userId, "習慣 " + value);
            default -> {
                return null;
            }
        }
        clearPending(userId);
        return response;
    }

    private String addSchedule(String userId, String value) {
        if (scheduleService.supports(value)) return scheduleService.handle(userId, value);
        NaturalLanguageService.Interpretation interpretation = naturalLanguageService.interpret(value);
        if (interpretation == null || interpretation.type() != NaturalLanguageService.Type.SCHEDULE) return null;
        return scheduleService.handle(userId, interpretation.command());
    }

    private String executeView(String userId, Domain domain, String text) {
        return switch (domain) {
            case TASK -> {
                if (text.contains("期限切れ") || text.contains("遅れ")) yield taskService.handle(userId, "期限切れ");
                if (text.contains("今週")) yield taskService.handle(userId, "今週のタスク");
                if (text.contains("今日")) yield taskService.handle(userId, "今日のタスク");
                yield taskService.handle(userId, "タスク一覧");
            }
            case SCHEDULE -> scheduleService.handle(userId, "予定一覧");
            case MEMO -> commandService.handle(userId, "メモ一覧");
            case SHOPPING -> commandService.handle(userId, "買い物一覧");
            case EXPENSE -> {
                if (text.contains("今日")) yield expenseService.handle(userId, "今日いくら");
                if (text.contains("カテゴリ") || text.contains("分類")) yield expenseService.handle(userId, "カテゴリ別");
                if (text.contains("一覧") || text.contains("履歴")) yield expenseService.handle(userId, "支出一覧");
                yield expenseService.handle(userId, "家計簿");
            }
            case HABIT -> {
                if (text.contains("統計") || text.contains("達成率") || text.contains("記録")) {
                    yield habitService.handle(userId, "習慣統計");
                }
                if (text.contains("一覧") || text.contains("全部")) yield habitService.handle(userId, "習慣一覧");
                yield habitService.handle(userId, "今日の習慣");
            }
            case WEATHER -> weatherService.handle(userId, text.contains("明日") ? "明日の天気" : "今日の天気");
            case STATS -> progressService.handle(userId, text.contains("今月") ? "今月の統計" : "統計");
            case PROFILE -> rpgService.handle(userId, "プロフィール");
        };
    }

    private Intent detectIntent(String text) {
        Domain domain = detectDomain(text);
        if (domain == null) return null;

        if (containsAny(text, ADD_PHRASES) && supportsAdd(domain)) return new Intent(domain, Action.ADD);
        if (containsAny(text, DELETE_PHRASES)) return new Intent(domain, Action.DELETE);
        if (containsAny(text, EDIT_PHRASES)) return new Intent(domain, Action.EDIT);
        if (containsAny(text, COMPLETE_PHRASES)) return new Intent(domain, Action.COMPLETE);
        if (containsAny(text, VIEW_PHRASES) || isBareView(text, domain)) return new Intent(domain, Action.VIEW);
        return null;
    }

    private boolean supportsAdd(Domain domain) {
        return domain == Domain.TASK || domain == Domain.SCHEDULE || domain == Domain.MEMO
                || domain == Domain.SHOPPING || domain == Domain.EXPENSE || domain == Domain.HABIT;
    }

    private Domain detectDomain(String text) {
        if (containsAny(text, List.of("タスク", "やること", "todo", "to-do"))) return Domain.TASK;
        if (containsAny(text, List.of("予定", "スケジュール"))) return Domain.SCHEDULE;
        if (text.contains("メモ")) return Domain.MEMO;
        if (containsAny(text, List.of("買い物", "買うもの", "買い物リスト"))) return Domain.SHOPPING;
        if (containsAny(text, List.of("家計簿", "支出", "出費", "お金"))) return Domain.EXPENSE;
        if (containsAny(text, List.of("習慣", "ルーティン"))) return Domain.HABIT;
        if (text.contains("天気")) return Domain.WEATHER;
        if (containsAny(text, List.of("統計", "利用状況", "成績"))) return Domain.STATS;
        if (containsAny(text, List.of("プロフィール", "レベル", "経験値", "称号"))) return Domain.PROFILE;
        return null;
    }

    private boolean isBareView(String text, Domain domain) {
        return switch (domain) {
            case TASK -> text.equals("タスクどうなってる") || text.equals("やることある？") || text.equals("やることある?");
            case SCHEDULE -> text.equals("予定ある？") || text.equals("予定ある?") || text.equals("予定どうなってる");
            case MEMO -> text.equals("メモある？") || text.equals("メモある?");
            case SHOPPING -> text.equals("買うものある？") || text.equals("買うものある?");
            case EXPENSE -> text.equals("家計簿") || text.equals("支出どうなってる") || text.equals("お金どうなってる");
            case HABIT -> text.equals("習慣") || text.equals("習慣どうなってる");
            case WEATHER -> true;
            case STATS, PROFILE -> true;
        };
    }

    private String extractDetail(String text, Domain domain, List<String> actionPhrases) {
        String result = text;
        for (String phrase : actionPhrases) result = result.replace(phrase, " ");
        for (String alias : aliases(domain)) result = result.replace(alias, " ");
        result = trimConversationalEndings(result);
        return cleanDetail(result);
    }

    private String cleanDetail(String value) {
        String result = normalize(value);
        result = trimConversationalEndings(result);
        result = result.replaceAll("^[をにへがはの、とっていう\\s]+", "");
        result = result.replaceAll("[をにへがはの、とっていう\\s]+$", "");
        return result.replaceAll("\\s+", " ").strip();
    }

    private String trimConversationalEndings(String value) {
        String result = value.strip();
        String[] endings = {
                "してもらえる？", "してもらえる?", "してもらえる", "してほしい", "お願いしたい",
                "なんだよね", "んだよね", "なんだけど", "んだけど", "と思ってる", "と思って",
                "お願い", "よろしく", "かな", "です", "だよね", "だよ"
        };
        boolean changed;
        do {
            changed = false;
            for (String ending : endings) {
                if (result.endsWith(ending)) {
                    result = result.substring(0, result.length() - ending.length()).strip();
                    changed = true;
                }
            }
        } while (changed);
        return result;
    }

    private List<String> aliases(Domain domain) {
        return switch (domain) {
            case TASK -> List.of("やること", "タスク", "todo", "to-do");
            case SCHEDULE -> List.of("スケジュール", "予定");
            case MEMO -> List.of("メモ");
            case SHOPPING -> List.of("買い物リスト", "買うもの", "買い物");
            case EXPENSE -> List.of("家計簿", "支出", "出費", "お金");
            case HABIT -> List.of("ルーティン", "習慣");
            case WEATHER -> List.of("天気");
            case STATS -> List.of("利用状況", "統計", "成績");
            case PROFILE -> List.of("プロフィール", "レベル", "経験値", "称号");
        };
    }

    private String addPrompt(Domain domain) {
        return switch (domain) {
            case TASK -> "もちろん。追加するタスクの内容を送ってね。\n例：資料作成 / 明日までに資料作成";
            case SCHEDULE -> "了解。日時と予定の内容を送ってね。\n例：明日19時 歯医者";
            case MEMO -> "いいよ。メモしておく内容を送ってね。";
            case SHOPPING -> "了解。買い物リストに入れるものを送ってね。";
            case EXPENSE -> "家計簿につける内容と金額を送ってね。\n例：昼1200円";
            case HABIT -> "追加する習慣を送ってね。\n例：筋トレ / 薬 毎日 21:00";
            default -> "内容を送ってね。";
        };
    }

    private String deleteGuide(Domain domain) {
        return switch (domain) {
            case TASK -> "削除するタスクを確認しよう。『タスク一覧』を開いて、例：タスク削除 2 と送ってね。";
            case SCHEDULE -> "『予定一覧』で番号を確認して、例：予定削除 2 この回だけ と送ってね。";
            case MEMO -> "『メモ一覧』で番号を確認して、例：メモ削除 2 と送ってね。";
            case SHOPPING -> "買い物の削除は、まず『買い物一覧』で対象を確認してね。";
            case EXPENSE -> "『支出一覧』で番号を確認して、例：支出削除 2 と送ってね。";
            case HABIT -> "『習慣一覧』で番号を確認して、例：習慣削除 2 と送ってね。";
            default -> "削除する対象を一覧で確認して、番号を指定してね。";
        };
    }

    private String editGuide(Domain domain) {
        return switch (domain) {
            case TASK -> "『タスク一覧』で番号を確認して、例：タスク変更 2 明日18時 資料提出 と送ってね。";
            case SCHEDULE -> "『予定一覧』で番号を確認して、例：予定変更 2 2026-08-01 20:00 会議 と送ってね。";
            case MEMO -> "『メモ一覧』で番号を確認して、例：メモ編集 2 新しい内容 と送ってね。";
            case EXPENSE -> "『支出一覧』で番号を確認して、例：支出編集 2 1500 昼ごはん と送ってね。";
            case HABIT -> "『習慣一覧』で番号を確認して、例：習慣編集 2 読書 平日 22:00 と送ってね。";
            default -> "変更する対象を一覧で確認して、番号と新しい内容を送ってね。";
        };
    }

    private String completeGuide(Domain domain) {
        return switch (domain) {
            case TASK -> "どのタスクか確認するね。『タスク一覧』を開いて、例：完了 2 と送ってね。";
            case HABIT -> "『今日の習慣』で番号を確認して、例：習慣達成 2 と送ってね。";
            case SHOPPING -> "『買い物一覧』で番号を確認して、例：購入 2 と送ってね。";
            default -> "対象を一覧で確認して、番号を指定してね。";
        };
    }

    private PendingAction pendingFor(Domain domain) {
        return switch (domain) {
            case TASK -> PendingAction.TASK_ADD;
            case SCHEDULE -> PendingAction.SCHEDULE_ADD;
            case MEMO -> PendingAction.MEMO_ADD;
            case SHOPPING -> PendingAction.SHOPPING_ADD;
            case EXPENSE -> PendingAction.EXPENSE_ADD;
            case HABIT -> PendingAction.HABIT_ADD;
            default -> throw new IllegalArgumentException("Unsupported pending domain: " + domain);
        };
    }

    private PendingAction loadPending(String userId) {
        jdbc.update("delete from conversation_contexts where line_user_id=? and expires_at<=current_timestamp", userId);
        String value = jdbc.query("select pending_action from conversation_contexts where line_user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        if (value == null) return null;
        try {
            return PendingAction.valueOf(value);
        } catch (IllegalArgumentException e) {
            clearPending(userId);
            return null;
        }
    }

    private void savePending(String userId, PendingAction action) {
        store.ensureUser(userId);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(CONTEXT_TTL));
        jdbc.update("""
                insert into conversation_contexts(line_user_id,pending_action,expires_at,updated_at)
                values (?,?,?,current_timestamp)
                on conflict(line_user_id) do update set
                    pending_action=excluded.pending_action,
                    expires_at=excluded.expires_at,
                    updated_at=current_timestamp
                """, userId, action.name(), expiresAt);
    }

    private void clearPending(String userId) {
        jdbc.update("delete from conversation_contexts where line_user_id=?", userId);
    }

    private boolean isCancel(String text) {
        return text.equals("やめる") || text.equals("やっぱやめる") || text.equals("キャンセル")
                || text.equals("取り消し") || text.equals("なしで") || text.equals("やめとく");
    }

    private boolean isCanonicalCommand(String text) {
        if (text.equals("ホーム") || text.equals("ヘルプ") || text.equals("通知設定")
                || text.equals("プライバシー") || text.equals("自分のデータ")) return true;
        String[] prefixes = {
                "メモ ", "メモ一覧", "メモ検索 ", "メモ編集 ", "メモ削除 ",
                "買い物 ", "買い物一覧", "購入 ",
                "支出 ", "支出一覧", "支出編集 ", "支出削除 ",
                "習慣 ", "習慣一覧", "習慣達成 ", "習慣編集 ", "習慣削除 ",
                "予定 ", "予定一覧", "予定変更 ", "予定削除 ",
                "毎日", "毎週", "毎月", "平日", "土日",
                "プロフィール", "実績", "今日のミッション", "カレンダー"
        };
        for (String prefix : prefixes) if (text.startsWith(prefix)) return true;
        return false;
    }

    private boolean containsAny(String text, List<String> values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private String help() {
        return """
                会話モードの例
                ━━━━━━━━━━
                タスク追加したい
                予定を入れたい
                メモしたい
                買い物リストに追加したい
                家計簿つけたい
                習慣を登録したい

                内容が足りないときは、次のメッセージを待つよ。
                『やっぱやめる』でキャンセルできるよ。
                """.strip();
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private enum Domain { TASK, SCHEDULE, MEMO, SHOPPING, EXPENSE, HABIT, WEATHER, STATS, PROFILE }
    private enum Action { ADD, VIEW, DELETE, EDIT, COMPLETE }
    private enum PendingAction { TASK_ADD, SCHEDULE_ADD, MEMO_ADD, SHOPPING_ADD, EXPENSE_ADD, HABIT_ADD }
    private record Intent(Domain domain, Action action) {}
}
