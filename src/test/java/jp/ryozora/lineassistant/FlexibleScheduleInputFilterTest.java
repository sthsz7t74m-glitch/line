package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlexibleScheduleInputFilterTest {

    @Test
    void acceptsNaturalScheduleInputsWithOrWithoutSpaces() {
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定 明日 18:00 映画")).isTrue();
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定明日18:00映画")).isTrue();
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定 7/30 昼 病院")).isTrue();
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定 18:00 買い物")).isTrue();
    }

    @Test
    void doesNotInterceptScheduleManagementCommands() {
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定一覧")).isFalse();
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定変更 1 明日")).isFalse();
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定削除 1")).isFalse();
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定変更案内 1")).isFalse();
        assertThat(FlexibleScheduleInputFilter.isFlexibleScheduleInput("予定追加")).isFalse();
    }
}
