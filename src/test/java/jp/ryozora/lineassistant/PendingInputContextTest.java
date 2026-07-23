package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingInputContextTest {

    @Test
    void storesAndClearsPendingInputPerUser() {
        PendingInputContext context = new PendingInputContext();

        context.start("user-a", PendingInputContext.Type.MEMO_EDIT, 2);
        context.start("user-b", PendingInputContext.Type.TASK_POSTPONE, 5);

        assertThat(context.get("user-a").type()).isEqualTo(PendingInputContext.Type.MEMO_EDIT);
        assertThat(context.get("user-a").number()).isEqualTo(2);
        assertThat(context.get("user-b").type()).isEqualTo(PendingInputContext.Type.TASK_POSTPONE);
        assertThat(context.get("user-b").number()).isEqualTo(5);

        context.clear("user-a");

        assertThat(context.get("user-a")).isNull();
        assertThat(context.get("user-b")).isNotNull();
    }
}
