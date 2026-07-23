package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SchedulePartialEditServiceTests {
    private final SchedulePartialEditService service = new SchedulePartialEditService(mock(JdbcTemplate.class));
    private final LocalDate date = LocalDate.of(2026, 7, 24);
    private final LocalTime time = LocalTime.of(12, 0);

    @Test
    void titleOnlyKeepsDateAndTime() {
        var patch = service.parse("遊園地", date, time, "会議");

        assertThat(patch.title()).isEqualTo("遊園地");
        assertThat(patch.date()).isEqualTo(date);
        assertThat(patch.time()).isEqualTo(time);
        assertThat(patch.titleChanged()).isTrue();
        assertThat(patch.dateChanged()).isFalse();
        assertThat(patch.timeChanged()).isFalse();
    }

    @Test
    void timeOnlyKeepsDateAndTitle() {
        var patch = service.parse("17:30", date, time, "会議");

        assertThat(patch.time()).isEqualTo(LocalTime.of(17, 30));
        assertThat(patch.date()).isEqualTo(date);
        assertThat(patch.title()).isEqualTo("会議");
        assertThat(patch.timeChanged()).isTrue();
        assertThat(patch.titleChanged()).isFalse();
    }

    @Test
    void dateOnlyKeepsTimeAndTitle() {
        var patch = service.parse("7/30", date, time, "会議");

        assertThat(patch.date()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(patch.time()).isEqualTo(time);
        assertThat(patch.title()).isEqualTo("会議");
        assertThat(patch.dateChanged()).isTrue();
        assertThat(patch.titleChanged()).isFalse();
    }

    @Test
    void naturalCombinedInputUpdatesAllProvidedFields() {
        var patch = service.parse("きょう17:30 遊園地", date, time, "会議");

        assertThat(patch.date()).isEqualTo(date);
        assertThat(patch.time()).isEqualTo(LocalTime.of(17, 30));
        assertThat(patch.title()).isEqualTo("遊園地");
        assertThat(patch.dateChanged()).isTrue();
        assertThat(patch.timeChanged()).isTrue();
        assertThat(patch.titleChanged()).isTrue();
    }

    @Test
    void japaneseClockIsAccepted() {
        var patch = service.parse("午後5時半", date, time, "会議");

        assertThat(patch.time()).isEqualTo(LocalTime.of(17, 30));
        assertThat(patch.title()).isEqualTo("会議");
    }
}
