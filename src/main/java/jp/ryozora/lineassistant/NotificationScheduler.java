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

    private final NotificationStore store;
    private final NotificationDataStore dataStore;
    private final WeatherService weather;
    private final LinePushService push;

    public NotificationScheduler(NotificationStore store, NotificationDataStore dataStore,
                                 WeatherService weather, LinePushService push) {
        this.store = store;
        this.dataStore = dataStore;
        this.weather = weather;
        this.push = push;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Tokyo")
    public void sendMorningNotifications() {
        LocalDate today = LocalDate.now(TOKYO);
        for (String userId : store.morningUsers(today)) {
            try {
                NotificationStore.Settings settings = store.get(userId);
                WeatherService.Forecast forecast = weather.fetch(settings.latitude(), settings.longitude());
                String rain = forecast.dailyRainProbability() >= 40
                        ? "\nRAIN  降水確率 " + forecast.dailyRainProbability() + "%　傘があると安心！"
                        : "\nRAIN  雨の心配は少なめ！";
                push.push(userId, "GOOD MORNING\n" + settings.area() + "の天気\n"
                        + "TEMP  最高 " + Math.round(forecast.maxTemperature()) + "℃ / 最低 "
                        + Math.round(forecast.minTemperature()) + "℃" + rain);
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
                push.push(userId, "RAIN NOTICE\n" + time + "ごろから雨の可能性あり！\n傘を忘れずに。");
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
                dataStore.upcomingSchedules(now.minusMinutes(1), now.plusMinutes(121));
        for (NotificationDataStore.UpcomingSchedule schedule : schedules) {
            for (int minutes : reminderMinutes(schedule.reminderMinutes())) {
                OffsetDateTime notifyAt = schedule.startsAt().minusMinutes(minutes).withSecond(0).withNano(0);
                if (notifyAt.isBefore(now.minusSeconds(30)) || notifyAt.isAfter(now.plusSeconds(30))) continue;
                String key = schedule.id() + ":" + minutes;
                if (!dataStore.reserveDelivery(schedule.userId(), "SCHEDULE", key)) continue;
                try {
                    String timing = minutes == 0 ? "まもなく開始" : "あと" + minutes + "分";
                    push.push(schedule.userId(), "SCHEDULE NOTICE\n"
                            + timing + "で「" + schedule.title() + "」\n"
                            + "START  " + schedule.startsAt().format(DateTimeFormatter.ofPattern("M/d(E) H:mm")));
                } catch (RuntimeException ignored) {
                    // Delivery reservation prevents duplicates; failures remain private.
                }
            }
        }
    }

    private int[] reminderMinutes(String raw) {
        if (raw == null || raw.isBlank()) return new int[]{30};
        return java.util.Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(v -> v.matches("\\d{1,3}"))
                .mapToInt(Integer::parseInt)
                .filter(v -> v >= 0 && v <= 1440)
                .distinct()
                .toArray();
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Tokyo")
    public void sendTaskNotifications() {
        LocalDate today = LocalDate.now(TOKYO);
        for (NotificationDataStore.DueTask task : dataStore.dueTasks(today, OFFSET)) {
            String key = today + ":" + task.id();
            if (!dataStore.reserveDelivery(task.userId(), "TASK", key)) continue;
            try {
                push.push(task.userId(), "TASK NOTICE\n今日が期限\n" + task.title());
            } catch (RuntimeException ignored) {
                // Do not expose task content in logs.
            }
        }
    }

    @Scheduled(cron = "0 0 21 * * *", zone = "Asia/Tokyo")
    public void sendNightNotifications() {
        LocalDate today = LocalDate.now(TOKYO);
        for (String userId : dataStore.nightUsers(today)) {
            try {
                NotificationDataStore.NightSummary summary = dataStore.nightSummary(userId, today, OFFSET);
                push.push(userId, "DAILY REPORT\n今日もお疲れ！\n"
                        + "DONE  完了タスク " + summary.completedTasks() + "件\n"
                        + "EXP   +" + summary.gainedExperience() + "\n"
                        + "NEXT  明日の予定 " + summary.tomorrowSchedules() + "件");
                dataStore.markNight(userId, today);
            } catch (RuntimeException ignored) {
                // Keep personal summaries out of logs.
            }
        }
    }
}
