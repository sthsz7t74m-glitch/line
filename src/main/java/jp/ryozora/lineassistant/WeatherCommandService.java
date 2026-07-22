package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
public class WeatherCommandService {
    private final NotificationStore notificationStore;
    private final WeatherService weatherService;

    public WeatherCommandService(NotificationStore notificationStore, WeatherService weatherService) {
        this.notificationStore = notificationStore;
        this.weatherService = weatherService;
    }

    public boolean isWeatherCommand(String raw) {
        if (raw == null) return false;
        String text = normalize(raw);
        return text.contains("天気") || text.contains("雨降る") || text.contains("雨ふる")
                || text.contains("傘いる") || text.contains("かさいる") || text.contains("傘必要")
                || text.contains("暑い") || text.contains("寒い") || text.contains("洗濯できる");
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        int dayOffset = text.contains("明日") || text.contains("あした") || text.contains("あす") ? 1 : 0;
        NotificationStore.Settings settings = notificationStore.get(userId);
        WeatherService.Forecast forecast = weatherService.fetch(settings.latitude(), settings.longitude(), dayOffset);

        String dayLabel = dayOffset == 1 ? "明日" : "今日";
        String icon = icon(forecast.weatherCode());
        String condition = condition(forecast.weatherCode());
        StringBuilder out = new StringBuilder();

        if (text.contains("傘") || text.contains("かさいる")) {
            if (forecast.dailyRainProbability() >= 50 || forecast.rainSoon()) {
                out.append("🌂 傘を持っていった方がよさそう！\n");
            } else if (forecast.dailyRainProbability() >= 30) {
                out.append("🌂 折りたたみ傘があると安心！\n");
            } else {
                out.append("☀️ 傘はたぶん必要なさそう！\n");
            }
        } else if (text.contains("雨降る") || text.contains("雨ふる")) {
            if (forecast.rainSoon()) {
                out.append("☔ 雨が降る可能性があるよ！\n");
            } else {
                out.append(forecast.dailyRainProbability() >= 40
                        ? "🌦 雨の可能性はあるよ！\n"
                        : "☀️ 雨の可能性は低そう！\n");
            }
        } else if (text.contains("洗濯できる")) {
            out.append(forecast.dailyRainProbability() < 30 && forecast.weatherCode() <= 3
                    ? "🧺 洗濯しやすい天気だよ！\n"
                    : "🧺 外干しはちょっと注意した方がよさそう！\n");
        }

        out.append("\n").append(icon).append(" ").append(dayLabel).append("の天気（")
                .append(settings.area()).append("）\n")
                .append(condition).append("\n")
                .append("最高 ").append(Math.round(forecast.maxTemperature())).append("℃ / 最低 ")
                .append(Math.round(forecast.minTemperature())).append("℃\n")
                .append("☔ 降水確率 ").append(forecast.dailyRainProbability()).append("%");

        if (forecast.currentTemperature() != null) {
            out.append("\n現在 ").append(Math.round(forecast.currentTemperature())).append("℃")
                    .append("（体感 ").append(Math.round(forecast.apparentTemperature())).append("℃）");
        }
        if (forecast.rainSoon()) {
            out.append("\n雨は ").append(forecast.rainAt().format(DateTimeFormatter.ofPattern("H時ごろ")))
                    .append("からの可能性あり");
        }
        out.append("\n\n").append(advice(forecast));
        return out.toString();
    }

    private String normalize(String text) {
        return text.replace('　', ' ').strip();
    }

    private String icon(int code) {
        if (code == 0) return "☀️";
        if (code <= 3) return "🌤";
        if (code <= 48) return "☁️";
        if (code <= 67) return "🌧";
        if (code <= 77) return "🌨";
        if (code <= 82) return "🌦";
        if (code <= 86) return "❄️";
        return "⛈";
    }

    private String condition(int code) {
        if (code == 0) return "晴れ";
        if (code <= 3) return "晴れ時々くもり";
        if (code <= 48) return "くもり・霧";
        if (code <= 67) return "雨";
        if (code <= 77) return "雪";
        if (code <= 82) return "にわか雨";
        if (code <= 86) return "にわか雪";
        return "雷雨";
    }

    private String advice(WeatherService.Forecast forecast) {
        if (forecast.maxTemperature() >= 33) return "🥵 かなり暑くなりそう。水分補給を忘れずに！";
        if (forecast.maxTemperature() >= 28) return "👕 暑くなりそう。薄着と水分補給がおすすめ！";
        if (forecast.minTemperature() <= 8) return "🧥 冷えそうだから上着があると安心！";
        if (forecast.dailyRainProbability() >= 50) return "🌂 傘を忘れずに！";
        return "✨ 過ごしやすそうな一日！";
    }
}
