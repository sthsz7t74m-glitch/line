package jp.ryozora.lineassistant;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotificationHistoryStore {
    private static final int LIMIT = 20;
    private static final ZoneOffset TOKYO = ZoneOffset.ofHours(9);
    private final Map<String, Deque<Entry>> entries = new ConcurrentHashMap<>();

    public void add(String userId, String type, String summary) {
        if (userId == null || userId.isBlank() || summary == null || summary.isBlank()) return;
        Deque<Entry> history = entries.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addFirst(new Entry(type == null || type.isBlank() ? "通知" : type,
                    summary.length() > 120 ? summary.substring(0, 120) : summary,
                    OffsetDateTime.now(TOKYO)));
            while (history.size() > LIMIT) history.removeLast();
        }
    }

    public List<Entry> list(String userId) {
        Deque<Entry> history = entries.get(userId);
        if (history == null) return List.of();
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public int count(String userId) {
        return list(userId).size();
    }

    public record Entry(String type, String summary, OffsetDateTime sentAt) { }
}
