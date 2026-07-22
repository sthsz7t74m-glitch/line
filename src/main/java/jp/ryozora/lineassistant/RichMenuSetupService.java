package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RichMenuSetupService {
    private static final Logger log = LoggerFactory.getLogger(RichMenuSetupService.class);
    private static final String MENU_NAME = "Benly Main v1";
    private static final String IMAGE_RESOURCE = "/richmenu/benly-richmenu-v1.png.b64";
    private static final String API = "https://api.line.me/v2/bot";
    private static final String DATA_API = "https://api-data.line.me/v2/bot";

    private final LineProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final boolean autoSetup;

    public RichMenuSetupService(LineProperties props, ObjectMapper mapper,
                                @Value("${line.bot.rich-menu.auto-setup:true}") boolean autoSetup) {
        this.props = props;
        this.mapper = mapper;
        this.autoSetup = autoSetup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupOnStartup() {
        if (!autoSetup || tokenMissing()) return;
        try {
            String richMenuId = setupDefaultMenu();
            log.info("Benly rich menu is ready: {}", richMenuId);
        } catch (Exception e) {
            // Do not stop the application when LINE temporarily rejects the setup request.
            log.warn("Benly rich menu setup failed: {}", e.getMessage());
        }
    }

    public synchronized String setupDefaultMenu() throws Exception {
        String existing = findExistingMenu();
        if (existing != null) {
            try {
                setDefault(existing);
                return existing;
            } catch (RuntimeException incompleteMenu) {
                deleteMenu(existing);
            }
        }

        String richMenuId = createMenu();
        try {
            uploadImage(richMenuId);
            setDefault(richMenuId);
            return richMenuId;
        } catch (Exception e) {
            deleteMenu(richMenuId);
            throw e;
        }
    }

    private String findExistingMenu() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(API + "/richmenu/list"))
                .header("Authorization", bearer())
                .GET().build());
        requireSuccess(response, "list rich menus");
        JsonNode menus = mapper.readTree(response.body()).path("richmenus");
        for (JsonNode menu : menus) {
            if (MENU_NAME.equals(menu.path("name").asText())) {
                return menu.path("richMenuId").asText();
            }
        }
        return null;
    }

    private String createMenu() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Map.of("width", 2500, "height", 1686));
        body.put("selected", true);
        body.put("name", MENU_NAME);
        body.put("chatBarText", "ベンリーを開く");
        body.put("areas", areas());

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(API + "/richmenu"))
                .header("Authorization", bearer())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build());
        requireSuccess(response, "create rich menu");
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

    private Map<String, Object> area(int x, int y, int width, int height, String label, String text) {
        return Map.of(
                "bounds", Map.of("x", x, "y", y, "width", width, "height", height),
                "action", Map.of("type", "message", "label", label, "text", text)
        );
    }

    private void uploadImage(String richMenuId) throws Exception {
        byte[] image = loadImage();
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(DATA_API + "/richmenu/" + richMenuId + "/content"))
                .header("Authorization", bearer())
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image))
                .build());
        requireSuccess(response, "upload rich menu image");
    }

    private byte[] loadImage() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(IMAGE_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Rich menu image resource is missing");
            String base64 = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("\\s+", "");
            return Base64.getDecoder().decode(base64);
        }
    }

    private void setDefault(String richMenuId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(API + "/user/all/richmenu/" + richMenuId))
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        requireSuccess(response, "set default rich menu");
    }

    private void deleteMenu(String richMenuId) {
        try {
            send(HttpRequest.newBuilder(URI.create(API + "/richmenu/" + richMenuId))
                    .header("Authorization", bearer())
                    .DELETE().build());
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LINE rich menu request interrupted", e);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            if (body.length() > 300) body = body.substring(0, 300);
            throw new IllegalStateException(operation + " failed: HTTP " + response.statusCode() + " " + body);
        }
    }

    private String bearer() {
        return "Bearer " + props.channelAccessToken();
    }

    private boolean tokenMissing() {
        return props.channelAccessToken() == null || props.channelAccessToken().isBlank();
    }
}
