package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContextualInputFilter extends OncePerRequestFilter {
    private static final Pattern START = Pattern.compile(
            "^(メモ編集案内|タスク変更案内|タスク延期案内|予定変更案内|習慣編集案内|支出編集案内)\\s+(\\d+)$");
    private static final Set<String> CANCEL = Set.of("キャンセル", "やめる", "取消", "取り消し");
    private static final Set<String> NAVIGATION = Set.of(
            "ホーム", "ベンリー", "トップ", "戻る", "← 戻る", "前へ戻る",
            "予定メニュー", "記録メニュー", "お金メニュー", "成長メニュー",
            "操作メニュー", "メモ一覧", "タスク一覧", "予定一覧", "習慣一覧", "支出一覧");

    private final LineWebhookSupport webhook;
    private final PendingInputContext context;
    private final BenlyCommandService commandService;
    private final TaskService taskService;
    private final SchedulePartialEditService schedulePartialEditService;
    private final ContextualPartialEditService partialEditService;
    private final UndoHistoryService undoHistoryService;

    public ContextualInputFilter(LineWebhookSupport webhook,
                                 PendingInputContext context,
                                 BenlyCommandService commandService,
                                 TaskService taskService,
                                 SchedulePartialEditService schedulePartialEditService,
                                 ContextualPartialEditService partialEditService,
                                 UndoHistoryService undoHistoryService) {
        this.webhook = webhook;
        this.context = context;
        this.commandService = commandService;
        this.taskService = taskService;
        this.schedulePartialEditService = schedulePartialEditService;
        this.partialEditService = partialEditService;
        this.undoHistoryService = undoHistoryService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        try {
            for (LineWebhookSupport.TextEvent event : webhook.textEvents(body)) {
                String input = event.text();
                Matcher starter = START.matcher(input);
                PendingInputContext.Pending pending = context.get(event.userId());
                if (!starter.matches() && pending == null) continue;

                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                if (starter.matches()) {
                    PendingInputContext.Type type = typeOf(starter.group(1));
                    int number = Integer.parseInt(starter.group(2));
                    context.start(event.userId(), type, number);
                    webhook.reply(event.replyToken(), List.of(promptMessage(type, number)));
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                if (CANCEL.contains(input)) {
                    context.clear(event.userId());
                    webhook.reply(event.replyToken(), List.of(textMessage("入力待ちをキャンセルしたよ。")));
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                if (isExplicitCommand(input) || NAVIGATION.contains(input)) {
                    context.clear(event.userId());
                    chain.doFilter(wrapped, response);
                    return;
                }

                Long historyId = undoHistoryService.capture(event.userId(), pending.type(), pending.number());
                String result = execute(event.userId(), pending, input);
                if (isFailure(result)) {
                    undoHistoryService.discard(historyId);
                    webhook.reply(event.replyToken(), List.of(retryMessage(pending, result)));
                } else {
                    context.clear(event.userId());
                    webhook.reply(event.replyToken(), List.of(successMessage(pending, result)));
                }
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            try {
                chain.doFilter(wrapped, response);
            } catch (Exception fallback) {
                if (fallback instanceof IOException io) throw io;
                throw new IOException(fallback);
            }
        }
    }

    private PendingInputContext.Type typeOf(String command) {
        return switch (command) {
            case "メモ編集案内" -> PendingInputContext.Type.MEMO_EDIT;
            case "タスク変更案内" -> PendingInputContext.Type.TASK_EDIT;
            case "タスク延期案内" -> PendingInputContext.Type.TASK_POSTPONE;
            case "予定変更案内" -> PendingInputContext.Type.SCHEDULE_EDIT;
            case "習慣編集案内" -> PendingInputContext.Type.HABIT_EDIT;
            case "支出編集案内" -> PendingInputContext.Type.EXPENSE_EDIT;
            default -> throw new IllegalArgumentException("Unsupported contextual command: " + command);
        };
    }

    private String execute(String userId, PendingInputContext.Pending pending, String input) {
        int number = pending.number();
        return switch (pending.type()) {
            case MEMO_EDIT -> commandService.handle(userId, "メモ編集 " + number + " " + input);
            case TASK_EDIT -> partialEditService.editTask(userId, number, input);
            case TASK_POSTPONE -> taskService.handle(userId, "タスク延期 " + number + " " + input);
            case SCHEDULE_EDIT -> schedulePartialEditService.edit(userId, number, input);
            case HABIT_EDIT -> partialEditService.editHabit(userId, number, input);
            case EXPENSE_EDIT -> partialEditService.editExpense(userId, number, input);
        };
    }

    private boolean isExplicitCommand(String input) {
        return input.matches("^(メモ編集|タスク変更|タスク延期|予定変更|習慣編集|支出編集)\\s+.*")
                || input.matches("^(メモ|タスク|予定|習慣|支出|買い物)(一覧|追加|削除|達成|完了|検索|全削除).*");
    }

    private boolean isFailure(String result) {
        if (result == null || result.isBlank()) return true;
        return result.contains("読み取れなかった") || result.contains("見つからなかった")
                || result.contains("確認してね") || result.startsWith("例：")
                || result.contains("の形で送ってね") || result.contains("できなかった")
                || result.contains("変更する項目を読み取れなかった");
    }

    private Map<String, Object> promptMessage(PendingInputContext.Type type, int number) {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(FlexUi.card("#F7F9FC", "10px", "xs", List.of(
                FlexUi.text(instruction(type), "sm", "bold", "#334E68"),
                FlexUi.text(contextHint(type), "xxs", "regular", "#718096")
        )));
        Map<String, Object> examples = exampleButtons(type);
        if (examples != null) body.add(examples);
        body.add(FlexUi.horizontal(List.of(
                FlexUi.button("キャンセル", "キャンセル", "#8793A5"),
                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
        )));

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#EEF2F7", "12px", "xs", List.of(
                        FlexUi.text(title(type), "lg", "bold", "#334E68"),
                        FlexUi.text("対象 No." + number + "・入力待ち中", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
        return FlexUi.flexMessage(title(type), bubble);
    }

    private Map<String, Object> retryMessage(PendingInputContext.Pending pending, String result) {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(FlexUi.card("#FFF9F0", "10px", "xs", List.of(
                FlexUi.text(result, "sm", "regular", "#526D82"),
                FlexUi.text(instruction(pending.type()), "xxs", "regular", "#718096")
        )));
        Map<String, Object> examples = exampleButtons(pending.type());
        if (examples != null) body.add(examples);
        body.add(FlexUi.button("キャンセル", "キャンセル", "#8793A5"));

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#FFF2DE", "12px", "xs", List.of(
                        FlexUi.text("入力を確認してね", "lg", "bold", "#C68A2B"),
                        FlexUi.text("対象 No." + pending.number() + "・入力待ちは継続中", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
        return FlexUi.flexMessage("入力を確認してね", bubble);
    }

    private Map<String, Object> successMessage(PendingInputContext.Pending pending, String result) {
        List<Map<String, Object>> lines = result.lines().map(String::strip)
                .filter(v -> !v.isBlank()).limit(10)
                .map(v -> FlexUi.text(v, "sm", v.startsWith("変更前") || v.startsWith("変更後") ? "bold" : "regular", "#526D82"))
                .toList();
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#E7F7F1", "12px", "xs", List.of(
                        FlexUi.text("✓ 変更したよ", "lg", "bold", "#2E9B6B"),
                        FlexUi.text(title(pending.type()), "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#F7FBFA", "10px", "xs", lines),
                        FlexUi.button("↩ 元に戻す", "元に戻す", "#C68A2B"),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("一覧を見る", listCommand(pending.type()), "#667EA8"),
                                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                        ))
                ))
        );
        return FlexUi.flexMessage("変更したよ", bubble);
    }

    private Map<String, Object> exampleButtons(PendingInputContext.Type type) {
        return switch (type) {
            case TASK_EDIT -> FlexUi.horizontal(List.of(
                    secondary("明日", "明日"), secondary("17:30", "17:30"), secondary("優先度高", "優先度高")));
            case TASK_POSTPONE -> FlexUi.horizontal(List.of(
                    secondary("1時間後", "1時間後"), secondary("明日", "明日"), secondary("3日後", "3日後")));
            case SCHEDULE_EDIT -> FlexUi.horizontal(List.of(
                    secondary("明日", "明日"), secondary("17:30", "17:30"), secondary("7/30", "7/30")));
            case HABIT_EDIT -> FlexUi.horizontal(List.of(
                    secondary("毎日", "毎日"), secondary("21:00", "21:00"), secondary("通知なし", "通知なし")));
            case EXPENSE_EDIT -> FlexUi.horizontal(List.of(
                    secondary("500円", "500円"), secondary("今日", "今日"), secondary("食費", "食費")));
            case MEMO_EDIT -> null;
        };
    }

    private Map<String, Object> secondary(String label, String message) {
        Map<String, Object> button = new java.util.LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "secondary");
        button.put("height", "sm");
        button.put("flex", 1);
        button.put("adjustMode", "shrink-to-fit");
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private Map<String, Object> textMessage(String text) {
        return Map.of("type", "text", "text", text);
    }

    private String title(PendingInputContext.Type type) {
        return switch (type) {
            case MEMO_EDIT -> "メモを編集";
            case TASK_EDIT -> "タスクを変更";
            case TASK_POSTPONE -> "タスクを延期";
            case SCHEDULE_EDIT -> "予定を変更";
            case HABIT_EDIT -> "習慣を編集";
            case EXPENSE_EDIT -> "支出を編集";
        };
    }

    private String instruction(PendingInputContext.Type type) {
        return switch (type) {
            case MEMO_EDIT -> "新しいメモ内容を送ってね";
            case TASK_EDIT -> "変更したい内容・期限・時刻・優先度だけ送ってね";
            case TASK_POSTPONE -> "延期先を送ってね（例：明日、1時間後）";
            case SCHEDULE_EDIT -> "変更したい内容・日付・時刻だけ送ってね";
            case HABIT_EDIT -> "変更したい内容・曜日・通知時刻だけ送ってね";
            case EXPENSE_EDIT -> "変更したい内容・金額・日付・分類だけ送ってね";
        };
    }

    private String contextHint(PendingInputContext.Type type) {
        return switch (type) {
            case MEMO_EDIT -> "次の文章を新しいメモ内容として使うよ";
            case TASK_POSTPONE -> "送った延期先だけを反映するよ";
            default -> "送られた項目だけ変更し、書かなかった項目はそのまま残すよ";
        };
    }

    private String listCommand(PendingInputContext.Type type) {
        return switch (type) {
            case MEMO_EDIT -> "メモ一覧";
            case TASK_EDIT, TASK_POSTPONE -> "タスク一覧";
            case SCHEDULE_EDIT -> "予定一覧";
            case HABIT_EDIT -> "習慣一覧";
            case EXPENSE_EDIT -> "支出一覧";
        };
    }
}
