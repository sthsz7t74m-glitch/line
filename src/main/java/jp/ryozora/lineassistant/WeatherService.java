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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class WeatherService {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public WeatherService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Forecast fetch(double latitude, double longitude) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + encode(latitude)
                    + "&longitude=" + encode(longitude)
                    + "&hourly=precipitation_probability,precipitation"
                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&timezone=Asia%2FTokyo&forecast_days=2";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Weather HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            JsonNode daily = root.path("daily");
            double max = daily.path("temperature_2m_max").path(0).asDouble();
            double min = daily.path("temperature_2m_min").path(0).asDouble();
            int dailyRain = daily.path("precipitation_probability_max").path(0).asInt();

            JsonNode times = root.path("hourly").path("time");
            JsonNode probabilities = root.path("hourly").path("precipitation_probability");
            JsonNode precipitation = root.path("hourly").path("precipitation");
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime rainAt = null;
            int rainProbability = 0;
            for (int i = 0; i < times.size(); i++) {
                LocalDateTime at = LocalDateTime.parse(times.path(i).asText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                if (at.isBefore(now.minusMinutes(5)) || at.isAfter(now.plusHours(3))) continue;
                int probability = probabilities.path(i).asInt();
                double amount = precipitation.path(i).asDouble();
                if (probability >= 50 || amount >= 0.1) {
                    rainAt = at;
                    rainProbability = probability;
                    break;
                }
            }
            return new Forecast(max, min, dailyRain, rainAt, rainProbability);
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
                           LocalDateTime rainAt, int rainProbability) {
        public boolean rainSoon() { return rainAt != null; }
    }
}
