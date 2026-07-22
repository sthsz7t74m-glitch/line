package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class NotificationTimeParserTest {

    @Test
    void parsesMorningTime() {
        assertEquals(LocalTime.of(7, 30), NotificationTimeParser.parseTime("7:30"));
        assertEquals(LocalTime.of(22, 5), NotificationTimeParser.parseTime("22：05"));
    }

    @Test
    void rejectsInvalidTime() {
        assertThrows(IllegalArgumentException.class,
                () -> NotificationTimeParser.parseTime("25:00"));
    }

    @Test
    void parsesQuietHoursAcrossMidnight() {
        NotificationTimeParser.QuietHours quiet =
                NotificationTimeParser.parseQuietHours("23:00〜7:00");
        assertEquals(LocalTime.of(23, 0), quiet.start());
        assertEquals(LocalTime.of(7, 0), quiet.end());
    }

    @Test
    void detectsQuietHoursAcrossMidnight() {
        LocalTime start = LocalTime.of(23, 0);
        LocalTime end = LocalTime.of(7, 0);
        assertTrue(NotificationTimeParser.isQuiet(LocalTime.of(23, 30), start, end));
        assertTrue(NotificationTimeParser.isQuiet(LocalTime.of(6, 59), start, end));
        assertFalse(NotificationTimeParser.isQuiet(LocalTime.of(7, 0), start, end));
        assertFalse(NotificationTimeParser.isQuiet(LocalTime.of(12, 0), start, end));
    }

    @Test
    void detectsQuietHoursWithinSameDay() {
        LocalTime start = LocalTime.of(12, 0);
        LocalTime end = LocalTime.of(14, 0);
        assertTrue(NotificationTimeParser.isQuiet(LocalTime.of(13, 0), start, end));
        assertFalse(NotificationTimeParser.isQuiet(LocalTime.of(14, 0), start, end));
    }
}
