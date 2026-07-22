package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class WeatherService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public WeatherService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Forecast fetch(double latitude, double longitude) {
        return fetch(latitude, longitude, 0);
    }

    public Forecast fetch(double latitude, double longitude, int dayOffset) {
        if (dayOffset < 0 || dayOffset > 1) throw new IllegalArgumentException("dayOffset must be 0 or 1");
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + encode(latitude)
                    + "&longitude=" + encode(longitude)
                    + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                    + "&hourly=precipitation_probability,precipitation,weather_code"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&timezone=Asia%2FTokyo&forecast_days=2";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Weather HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            JsonNode daily = root.path("daily");
            double max = daily.path("temperature_2m_max").path(dayOffset).asDouble();
            double min = daily.path("temperature_2m_min").path(dayOffset).asDouble();
            int dailyRain = daily.path("precipitation_probability_max").path(dayOffset).asInt();
            int weatherCode = daily.path("weather_code").path(dayOffset).asInt();

            JsonNode current = root.path("current");
            Double currentTemperature = dayOffset == 0 ? current.path("temperature_2m").asDouble() : null;
            Double apparentTemperature = dayOffset == 0 ? current.path("apparent_temperature").asDouble() : null;
            Double windSpeed = dayOffset == 0 ? current.path("wind_speed_10m").asDouble() : null;

            JsonNode times = root.path("hourly").path("time");
            JsonNode probabilities = root.path("hourly").path("precipitation_probability");
            JsonNode precipitation = root.path("hourly").path("precipitation");
            LocalDate targetDate = LocalDate.now(TOKYO).plusDays(dayOffset);
            LocalDateTime now = LocalDateTime.now(TOKYO);
            LocalDateTime rainAt = null;
            int rainProbability = 0;
            for (int i = 0; i < times.size(); i++) {
                LocalDateTime at = LocalDateTime.parse(times.path(i).asText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                if (!at.toLocalDate().equals(targetDate)) continue;
                if (dayOffset == 0 && at.isBefore(now.minusMinutes(5))) continue;
                int probability = probabilities.path(i).asInt();
                double amount = precipitation.path(i).asDouble();
                if (probability >= 50 || amount >= 0.1) {
                    rainAt = at;
                    rainProbability = probability;
                    break;
                }
            }
            return new Forecast(max, min, dailyRain, weatherCode, currentTemperature,
                    apparentTemperature, windSpeed, rainAt, rainProbability);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Weather request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Weather request failed", e);
        }
    }

    private String encode(double value) {
        return URLEncoder.encode(Double.toString(value), StandardCharsets.UTF_8);
    }

    public record Forecast(double maxTemperature, double minTemperature, int dailyRainProbability,
                           int weatherCode, Double currentTemperature, Double apparentTemperature,
                           Double windSpeed, LocalDateTime rainAt, int rainProbability) {
        public boolean rainSoon() { return rainAt != null; }
    }
}
