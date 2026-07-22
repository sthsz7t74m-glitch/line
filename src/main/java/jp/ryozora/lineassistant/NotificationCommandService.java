package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

@Service
public class NotificationCommandService {
    private final NotificationStore store;

    public NotificationCommandService(NotificationStore store) {
        this.store = store;
    }

    public boolean isSettingsCommand(String text) {
        return text.equals("通知設定") || text.equals("通知") || text.startsWith("通知切替 ");
    }

    public NotificationStore.Settings process(String userId, String text) {
        if (text.startsWith("通知切替 ")) {
            String type = text.substring("通知切替 ".length()).strip();
            return store.toggle(userId, type);
        }
        return store.get(userId);
    }
}
