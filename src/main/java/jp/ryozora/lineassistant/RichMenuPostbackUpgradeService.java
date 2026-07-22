package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RichMenuPostbackUpgradeService {
    private static final Logger log = LoggerFactory.getLogger(RichMenuPostbackUpgradeService.class);
    private static final String MENU_NAME = "Benly Message v1";
    private static final String API = "https://api.line.me/v2/bot";
    private static final String DATA_API = "https://api-data.line.me/v2/bot";

    private final LineProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AtomicBoolean attempted = new AtomicBoolean();

    public RichMenuPostbackUpgradeService(LineProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 86_400_000)
    public void upgradeOnceAfterStartup() {
        if (!attempted.compareAndSet(false, true) || tokenMissing()) return;
        try {
            upgrade();
            log.info("Benly message rich menu is ready");
        } catch (Exception e) {
            log.warn("Benly message rich menu recovery failed: {}", safe(e));
        }
    }

    public synchronized String upgrade() throws Exception {
        JsonNode menus = listMenus();
        String existing = null;
        String sourceId = null;
        for (JsonNode menu : menus) {
            String name = menu.path("name").asText();
            String id = menu.path("richMenuId").asText();
            if (MENU_NAME.equals(name)) existing = id;
            if (sourceId == null && name.startsWith("Benly")) sourceId = id;
        }

        if (existing != null) {
            activate(existing);
            return existing;
        }
        if (sourceId == null) throw new IllegalStateException("Benly rich menu image source was not found");

        byte[] image = getImage(sourceId);
        String id = createMenu();
        try {
            uploadImage(id, image);
            activate(id);
            deleteOtherBenlyMenus(id);
            return id;
        } catch (Exception e) {
            deleteMenu(id);
            throw e;
        }
    }

    private String createMenu() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Map.of("width", 2500, "height", 1686));
        body.put("selected", true);
        body.put("name", MENU_NAME);
        body.put("chatBarText", "ベンリーを開く");
        body.put("areas", areas());

        HttpResponse<String> response = sendText(HttpRequest.newBuilder(URI.create(API + "/richmenu"))
                .header("Authorization", bearer())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build());
        requireSuccess(response, "create message rich menu");
        String id = mapper.readTree(response.body()).path("richMenuId").asText();
        if (id.isBlank()) throw new IllegalStateException("LINE returned no richMenuId");
        return id;
    }

    private List<Map<String, Object>> areas() {
        List<Map<String, Object>> areas = new ArrayList<>();
        areas.add(area(0, 0, 833, 843, "今日・予定", "予定メニュー"));
        areas.add(area(833, 0, 833, 843, "メモ・タスク", "記録メニュー"));
        areas.add(area(1666, 0, 834, 843, "お金・買い物", "お金メニュー"));
        areas.add(area(0, 843, 833, 843, "習慣・成長", "成長メニュー"));
        areas.add(area(833, 843, 833, 843, "通知設定", "通知設定"));
        areas.add(area(1666, 843, 834, 843, "ホーム", "ホーム"));
        return areas;
    }

    private Map<String, Object> area(int x, int y, int width, int height, String label, String command) {
        return Map.of(
                "bounds", Map.of("x", x, "y", y, "width", width, "height", height),
                "action", Map.of("type", "message", "label", label, "text", command)
        );
    }

    private void activate(String id) throws Exception {
        postEmpty(URI.create(API + "/user/all/richmenu/" + id), "set default message menu");
        if (props.ownerUserId() != null && !props.ownerUserId().isBlank()) {
            postEmpty(URI.create(API + "/user/" + props.ownerUserId() + "/richmenu/" + id),
                    "link message menu to owner");
        }
    }

    private JsonNode listMenus() throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(URI.create(API + "/richmenu/list"))
                .header("Authorization", bearer()).GET().build());
        requireSuccess(response, "list rich menus");
        return mapper.readTree(response.body()).path("richmenus");
    }

    private byte[] getImage(String id) throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                        URI.create(DATA_API + "/richmenu/" + id + "/content"))
                .header("Authorization", bearer()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2 || response.body() == null || response.body().length < 8) {
            throw new IllegalStateException("get existing rich menu image failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void uploadImage(String id, byte[] image) throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(
                        URI.create(DATA_API + "/richmenu/" + id + "/content"))
                .header("Authorization", bearer())
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image)).build());
        requireSuccess(response, "upload message rich menu image");
    }

    private void postEmpty(URI uri, String operation) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", bearer());
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(0);
        try {
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status / 100 != 2) {
                throw new IllegalStateException(operation + " failed: HTTP " + status + " " + body);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readBody(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void deleteOtherBenlyMenus(String keepId) throws Exception {
        for (JsonNode menu : listMenus()) {
            String id = menu.path("richMenuId").asText();
            String name = menu.path("name").asText();
            if (!keepId.equals(id) && name.startsWith("Benly")) deleteMenu(id);
        }
    }

    private void deleteMenu(String id) {
        try {
            sendText(HttpRequest.newBuilder(URI.create(API + "/richmenu/" + id))
                    .header("Authorization", bearer()).DELETE().build());
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private HttpResponse<String> sendText(HttpRequest request) throws Exception {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LINE rich menu request interrupted", e);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(operation + " failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private String bearer() {
        return "Bearer " + props.channelAccessToken();
    }

    private boolean tokenMissing() {
        return props.channelAccessToken() == null || props.channelAccessToken().isBlank();
    }

    private String safe(Throwable e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.replaceAll("[\\r\\n\\t]+", " ").strip();
    }
}
