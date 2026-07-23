package jp.ryozora.lineassistant;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingInputContext {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public void start(String userId, Type type, int number) {
        pending.put(userId, new Pending(type, number, Instant.now()));
    }

    public Pending get(String userId) {
        Pending value = pending.get(userId);
        if (value == null) return null;
        if (value.startedAt().plus(TTL).isBefore(Instant.now())) {
            pending.remove(userId, value);
            return null;
        }
        return value;
    }

    public void clear(String userId) {
        pending.remove(userId);
    }

    public enum Type {
        MEMO_EDIT,
        TASK_EDIT,
        TASK_POSTPONE,
        SCHEDULE_EDIT,
        HABIT_EDIT,
        EXPENSE_EDIT
    }

    public record Pending(Type type, int number, Instant startedAt) { }
}
