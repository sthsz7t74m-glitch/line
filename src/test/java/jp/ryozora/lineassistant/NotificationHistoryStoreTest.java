package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationHistoryStoreTest {

    @Test
    void keepsLatestTwentyEntriesPerUser() {
        NotificationHistoryStore store = new NotificationHistoryStore();
        for (int i = 1; i <= 25; i++) {
            store.add("user-a", "予定", "通知" + i);
        }

        assertThat(store.list("user-a")).hasSize(20);
        assertThat(store.list("user-a").get(0).summary()).isEqualTo("通知25");
        assertThat(store.list("user-a").get(19).summary()).isEqualTo("通知6");
    }

    @Test
    void separatesUsers() {
        NotificationHistoryStore store = new NotificationHistoryStore();
        store.add("user-a", "雨", "東京");
        store.add("user-b", "習慣", "読書");

        assertThat(store.list("user-a")).extracting(NotificationHistoryStore.Entry::summary)
                .containsExactly("東京");
        assertThat(store.list("user-b")).extracting(NotificationHistoryStore.Entry::summary)
                .containsExactly("読書");
    }
}
