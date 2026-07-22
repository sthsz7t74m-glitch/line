package jp.ryozora.lineassistant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class NotificationScheduler {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);
    private static final int ONE_MONTH_MINUTES = 30 * 24 * 60;
    private static final int ONE_WEEK_MINUTES = 7 * 24 * 60;
    private static final int ONE_DAY_MINUTES = 24 * 60;
    private static final int[] STANDARD_REMINDERS = {
            ONE_MONTH_MINUTES,
            ONE_WEEK_MINUTES,
            ONE_DAY_MINUTES,
            60,
            5,
            0
    };

    private final NotificationStore store;
    private final NotificationDataStore dataStore;
    private final WeatherService weather;
    private final LinePushService push;
    private final AiSecretaryService secretary;
    private final HabitService habitService;

    public NotificationScheduler(NotificationStore store, NotificationDataStore dataStore,
                                 WeatherService weather, LinePushService push,
                                 AiSecretaryService secretary, HabitService habitService) {
        this.store = store;
        this.dataStore = dataStore;
        this.weather = weather;
        this.push = push;
        this.secretary = secretary;
        this.habitService = habitService;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Tokyo")
    public void sendMorningNotifications() {
        LocalDate today = LocalDate.now(TOKYO);
        for (String userId : store.morningUsers(today)) {
            try {
                push.pushMorningBriefing(userId, secretary.morningBriefing(userId));
                store.markMorning(userId, today);
            } catch (RuntimeException ignored) {
                // Do not expose user content or identifiers in scheduled-job logs.
            }
        }
    }

    @Scheduled(cron = "0 */30 * * * *", zone = "Asia/Tokyo")
    public void checkRainNotifications() {
        LocalDateTime now = LocalDateTime.now(TOKYO);
        for (String userId : store.rainUsers(now.minusHours(6))) {
            try {
                NotificationStore.Settings settings = store.get(userId);
                WeatherService.Forecast forecast = weather.fetch(settings.latitude(), settings.longitude());
                if (!forecast.rainSoon()) continue;
                String time = forecast.rainAt().format(DateTimeFormatter.ofPattern("H時"));
                push.push(userId, "雨のお知らせ\n" + time + "ごろから雨の可能性があるよ。\n傘を忘れずに！");
                store.markRain(userId, now);
            } catch (RuntimeException ignored) {
                // Keep scheduled jobs alive without exposing personal data.
            }
        }
    }

    @Scheduled(cron = "0 */1 * * * *", zone = "Asia/Tokyo")
    public void sendScheduleNotifications() {
        OffsetDateTime now = OffsetDateTime.now(OFFSET).withSecond(0).withNano(0);
        List<NotificationDataStore.UpcomingSchedule> schedules =
                dataStore.upcomingSchedules(now.minusMinutes(1), now.plusMinutes(ONE_MONTH_MINUTES + 1L));
        for (NotificationDataStore.UpcomingSchedule schedule : schedules) {
            for (int minutes : reminderMinutes(schedule.reminderMinutes())) {
                OffsetDateTime notifyAt = schedule.startsAt().minusMinutes(minutes).withSecond(0).withNano(0);
                if (notifyAt.isBefore(now.minusSeconds(30)) || notifyAt.isAfter(now.plusSeconds(30))) continue;
                String key = schedule.id() + ":" + minutes;
                if (!dataStore.reserveDelivery(schedule.userId(), "SCHEDULE", key)) continue;
                try {
                    push.pushScheduleReminder(schedule.userId(), schedule.id(), schedule.title(),
                            schedule.startsAt(), minutes);
                } catch (RuntimeException ignored) {
                    // Delivery reservation prevents duplicates; failures remain private.
                }
            }
        }
    }

    @Scheduled(cron = "0 */1 * * * *", zone = "Asia/Tokyo")
    public void sendTaskNotifications() {
        OffsetDateTime now = OffsetDateTime.now(OFFSET).withSecond(0).withNano(0);
        List<NotificationDataStore.UpcomingTask> tasks =
                dataStore.upcomingTasks(now.minusMinutes(1), now.plusMinutes(ONE_MONTH_MINUTES + 1L));
        for (NotificationDataStore.UpcomingTask task : tasks) {
            for (int minutes : taskReminderMinutes(task.reminderMinutes())) {
                OffsetDateTime notifyAt = task.dueAt().minusMinutes(minutes).withSecond(0).withNano(0);
                if (notifyAt.isBefore(now.minusSeconds(30)) || notifyAt.isAfter(now.plusSeconds(30))) continue;
                String key = task.id() + ":" + minutes;
                if (!dataStore.reserveDelivery(task.userId(), "TASK", key)) continue;
                try {
                    push.pushTaskReminder(task.userId(), task.id(), task.title(), task.priority(),
                            task.dueAt(), minutes);
                } catch (RuntimeException ignored) {
                    // Keep task content and user identifiers out of scheduler logs.
                }
            }
        }
    }

    @Scheduled(cron = "0 */1 * * * *", zone = "Asia/Tokyo")
    public void sendHabitNotifications() {
        LocalDateTime now = LocalDateTime.now(TOKYO).withSecond(0).withNano(0);
        for (HabitService.HabitReminder habit : habitService.dueReminders(now)) {
            String key = habit.id() + ":" + now.toLocalDate();
            if (!dataStore.reserveDelivery(habit.userId(), "HABIT", key)) continue;
            try {
                push.pushHabitReminder(habit.userId(), habit.id(), habit.name());
            } catch (RuntimeException ignored) {
                // Keep habit names and user identifiers out of scheduler logs.
            }
        }
    }

    private int[] reminderMinutes(String raw) {
        // Empty text is the explicit "通知なし" setting.
        if (raw != null && raw.isBlank()) return new int[0];

        // Null and the former 30-minute default are upgraded to the new standard policy.
        if (raw == null || raw.strip().equals("30")) return STANDARD_REMINDERS.clone();

        String normalized = raw.strip();
        if (normalized.startsWith("C:") || normalized.startsWith("D:")) {
            normalized = normalized.substring(2);
        }

        return parseReminderValues(normalized);
    }

    private int[] taskReminderMinutes(String raw) {
        if (raw != null && raw.isBlank()) return new int[0];
        return parseReminderValues(raw == null ? "1440,60,0" : raw);
    }

    private int[] parseReminderValues(String raw) {
        return java.util.Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(v -> v.matches("\\d{1,5}"))
                .mapToInt(Integer::parseInt)
                .filter(v -> v >= 0 && v <= ONE_MONTH_MINUTES)
                .distinct()
                .toArray();
    }

    @Scheduled(cron = "0 0 21 * * *", zone = "Asia/Tokyo")
    public void sendNightNotifications() {
        LocalDate today = LocalDate.now(TOKYO);
        for (String userId : dataStore.nightUsers(today)) {
            try {
                NotificationDataStore.NightSummary summary = dataStore.nightSummary(userId, today, OFFSET);
                push.push(userId, "今日もお疲れさま！\n"
                        + "完了タスク " + summary.completedTasks() + "件\n"
                        + "経験値 +" + summary.gainedExperience() + "\n"
                        + "明日の予定 " + summary.tomorrowSchedules() + "件");
                dataStore.markNight(userId, today);
            } catch (RuntimeException ignored) {
                // Keep personal summaries out of logs.
            }
        }
    }
}
