package jp.ryozora.lineassistant;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingScheduleCreation {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final Map<String, Pending> values = new ConcurrentHashMap<>();

    public void start(String userId, String partial) {
        values.put(userId, new Pending(partial == null ? "" : partial.strip(), Instant.now()));
    }

    public Pending get(String userId) {
        Pending pending = values.get(userId);
        if (pending == null) return null;
        if (pending.startedAt().plus(TTL).isBefore(Instant.now())) {
            values.remove(userId, pending);
            return null;
        }
        return pending;
    }

    public void clear(String userId) {
        values.remove(userId);
    }

    public record Pending(String partial, Instant startedAt) { }
}
