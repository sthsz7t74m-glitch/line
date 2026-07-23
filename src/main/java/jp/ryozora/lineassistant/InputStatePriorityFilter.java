package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InputStatePriorityFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of("入力状態", "編集中");
    private static final Duration TTL = Duration.ofMinutes(10);

    private final LineWebhookSupport webhook;
    private final PendingInputContext pendingInput;

    public InputStatePriorityFilter(LineWebhookSupport webhook, PendingInputContext pendingInput) {
        this.webhook = webhook;
        this.pendingInput = pendingInput;
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
                if (!COMMANDS.contains(event.text())) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                webhook.reply(event.replyToken(), List.of(message(event.userId())));
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

    private Map<String, Object> message(String userId) {
        PendingInputContext.Pending pending = pendingInput.get(userId);
        List<Map<String, Object>> body = new ArrayList<>();
        if (pending == null) {
            body.add(FlexUi.card("#F7F9FC", "12px", "xs", List.of(
                    FlexUi.text("現在、入力待ちはないよ", "md", "bold", "#526D82"),
                    FlexUi.text("編集ボタンを押すと、ここで対象と残り時間を確認できるよ", "xxs", "regular", "#8A96A6")
            )));
        } else {
            long elapsed = Math.max(0, Duration.between(pending.startedAt(), Instant.now()).toMinutes());
            long remaining = Math.max(0, TTL.toMinutes() - elapsed);
            body.add(FlexUi.card("#FFF9F0", "12px", "xs", List.of(
                    FlexUi.text(label(pending.type()), "lg", "bold", "#C68A2B"),
                    FlexUi.text("対象 No." + pending.number(), "sm", "bold", "#526D82"),
                    FlexUi.text("残り約" + remaining + "分", "xs", "regular", "#8A6B3F")
            )));
            body.add(FlexUi.button("キャンセル", "キャンセル", "#B76A73"));
        }
        body.add(FlexUi.button("🏠 ホーム", "ホーム", "#8793A5"));
        return FlexUi.flexMessage("入力状態", FlexUi.bubble(
                FlexUi.vertical(pending == null ? "#EEF2F7" : "#FFF2DE", "14px", "xs", List.of(
                        FlexUi.text("入力状態", "xl", "bold", pending == null ? "#526D82" : "#C68A2B")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        ));
    }

    private String label(PendingInputContext.Type type) {
        return switch (type) {
            case MEMO_EDIT -> "メモを編集中";
            case TASK_EDIT -> "タスクを変更中";
            case TASK_POSTPONE -> "タスクを延期中";
            case SCHEDULE_EDIT -> "予定を変更中";
            case HABIT_EDIT -> "習慣を編集中";
            case EXPENSE_EDIT -> "支出を編集中";
        };
    }
}
