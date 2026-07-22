package jp.ryozora.lineassistant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class NotificationScheduler {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final NotificationStore store;
    private final WeatherService weather;
    private final LinePushService push;

    public NotificationScheduler(NotificationStore store, WeatherService weather, LinePushService push) {
        this.store = store;
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
                        ? "\n☔ 降水確率 " + forecast.dailyRainProbability() + "%　傘があると安心！"
                        : "\n🌂 雨の心配は少なめ！";
                String message = "☀️ おっはー！\n"
                        + settings.area() + "の天気\n"
                        + "最高 " + Math.round(forecast.maxTemperature()) + "℃ / 最低 "
                        + Math.round(forecast.minTemperature()) + "℃" + rain;
                push.push(userId, message);
                store.markMorning(userId, today);
            } catch (RuntimeException ignored) {
                // Do not include user content or identifiers in logs.
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
                push.push(userId, "☔ 雨のお知らせ\n" + time + "ごろから雨の可能性あり！\n傘を忘れずに🌂");
                store.markRain(userId, now);
            } catch (RuntimeException ignored) {
                // Keep scheduled jobs alive without exposing personal data.
            }
        }
    }
}
