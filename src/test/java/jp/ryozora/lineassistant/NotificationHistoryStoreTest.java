package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "line.bot.channel-secret=test",
        "line.bot.channel-access-token=test"
})
@Transactional
class NotificationHistoryStoreTest {

    @Autowired
    NotificationHistoryStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void keepsLatestTwentyEntriesPerUser() {
        String userId = "history-user-a";
        jdbc.update("delete from notification_history where line_user_id=?", userId);
        for (int i = 1; i <= 25; i++) {
            store.add(userId, "予定", "通知" + i);
        }

        assertThat(store.list(userId)).hasSize(20);
        assertThat(store.list(userId).get(0).summary()).isEqualTo("通知25");
        assertThat(store.list(userId).get(19).summary()).isEqualTo("通知6");
        assertThat(store.count(userId)).isEqualTo(25);
    }

    @Test
    void separatesUsers() {
        String userA = "history-user-b-a";
        String userB = "history-user-b-b";
        jdbc.update("delete from notification_history where line_user_id in (?,?)", userA, userB);
        store.add(userA, "雨", "東京");
        store.add(userB, "習慣", "読書");

        assertThat(store.list(userA)).extracting(NotificationHistoryStore.Entry::summary)
                .containsExactly("東京");
        assertThat(store.list(userB)).extracting(NotificationHistoryStore.Entry::summary)
                .containsExactly("読書");
    }
}
