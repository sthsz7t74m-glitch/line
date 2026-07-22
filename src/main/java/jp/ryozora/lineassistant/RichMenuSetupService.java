package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RichMenuSetupService {
    private static final Logger log = LoggerFactory.getLogger(RichMenuSetupService.class);
    private static final String MENU_NAME = "Benly Main v2";
    private static final String API = "https://api.line.me/v2/bot";
    private static final String DATA_API = "https://api-data.line.me/v2/bot";
    private static final int WIDTH = 2500;
    private static final int HEIGHT = 1686;

    private final LineProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final boolean autoSetup;
    private volatile String lastStatus = "not-started";

    public RichMenuSetupService(LineProperties props, ObjectMapper mapper,
                                @Value("${line.bot.rich-menu.auto-setup:true}") boolean autoSetup) {
        this.props = props;
        this.mapper = mapper;
        this.autoSetup = autoSetup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupOnStartup() {
        if (!autoSetup) {
            lastStatus = "disabled";
            log.info("Benly rich menu setup is disabled");
            return;
        }
        if (tokenMissing()) {
            lastStatus = "token-missing";
            log.warn("Benly rich menu setup skipped: LINE channel access token is missing");
            return;
        }
        try {
            String richMenuId = setupDefaultMenu();
            lastStatus = "ready:" + richMenuId;
            log.info("Benly rich menu is ready: {}", richMenuId);
        } catch (Exception e) {
            lastStatus = "failed:" + e.getClass().getSimpleName();
            log.warn("Benly rich menu setup failed: {}", e.getMessage());
        }
    }

    public String status() {
        return lastStatus;
    }

    public synchronized String setupDefaultMenu() throws Exception {
        if (tokenMissing()) throw new IllegalStateException("LINE channel access token is missing");

        String existing = findExistingMenu();
        if (existing != null) {
            setDefault(existing);
            lastStatus = "ready:" + existing;
            return existing;
        }

        String richMenuId = createMenu();
        try {
            byte[] image = generateMenuImage();
            log.info("Generated rich menu PNG: {} bytes", image.length);
            uploadImage(richMenuId, image);
            setDefault(richMenuId);
            deleteLegacyMenus(richMenuId);
            lastStatus = "ready:" + richMenuId;
            return richMenuId;
        } catch (Exception e) {
            deleteMenu(richMenuId);
            lastStatus = "failed:" + e.getClass().getSimpleName();
            throw e;
        }
    }

    private String findExistingMenu() throws Exception {
        JsonNode menus = listMenus();
        for (JsonNode menu : menus) {
            if (MENU_NAME.equals(menu.path("name").asText())) {
                return menu.path("richMenuId").asText();
            }
        }
        return null;
    }

    private JsonNode listMenus() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(API + "/richmenu/list"))
                .header("Authorization", bearer())
                .GET().build());
        requireSuccess(response, "list rich menus");
        return mapper.readTree(response.body()).path("richmenus");
    }

    private String createMenu() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Map.of("width", WIDTH, "height", HEIGHT));
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

    private byte[] generateMenuImage() throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            String[][] labels = {
                    {"TODAY", "SCHEDULE"},
                    {"MEMO", "TASK"},
                    {"MONEY", "SHOPPING"},
                    {"HABIT", "GROWTH"},
                    {"NOTIFICATIONS", "SETTINGS"},
                    {"BENLY", "HOME"}
            };
            Color[] backgrounds = {
                    new Color(220, 235, 255), new Color(221, 245, 236), new Color(255, 240, 217),
                    new Color(238, 229, 255), new Color(230, 240, 255), new Color(233, 237, 244)
            };
            Color[] accents = {
                    new Color(46, 111, 196), new Color(46, 155, 107), new Color(216, 137, 22),
                    new Color(121, 87, 199), new Color(86, 126, 199), new Color(79, 96, 119)
            };

            int cellWidth = WIDTH / 3;
            int cellHeight = HEIGHT / 2;
            Font titleFont = new Font("SansSerif", Font.BOLD, 91);
            Font subFont = new Font("SansSerif", Font.BOLD, 58);

            for (int index = 0; index < 6; index++) {
                int row = index / 3;
                int col = index % 3;
                int x = col * cellWidth;
                int y = row * cellHeight;
                int width = col == 2 ? WIDTH - x : cellWidth;

                g.setColor(backgrounds[index]);
                g.fillRect(x, y, width, cellHeight);
                g.setColor(accents[index]);
                g.fillRect(x, y, width, 45);

                g.setFont(titleFont);
                drawCentered(g, labels[index][0], x, y + 300, width);
                g.setFont(subFont);
                drawCentered(g, labels[index][1], x, y + 445, width);
            }

            g.setColor(Color.WHITE);
            g.fillRect(WIDTH / 3 - 4, 0, 8, HEIGHT);
            g.fillRect((WIDTH / 3) * 2 - 4, 0, 8, HEIGHT);
            g.fillRect(0, HEIGHT / 2 - 4, WIDTH, 8);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("PNG writer is unavailable");
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 0x50
                || bytes[2] != 0x4E || bytes[3] != 0x47) {
            throw new IllegalStateException("Generated rich menu image is not a PNG");
        }
        if (bytes.length > 1024 * 1024) {
            throw new IllegalStateException("Generated rich menu image exceeds 1 MB");
        }
        return bytes;
    }

    private void drawCentered(Graphics2D g, String text, int x, int baseline, int width) {
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        g.drawString(text, x + Math.max(0, (width - textWidth) / 2), baseline);
    }

    private void uploadImage(String richMenuId, byte[] image) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(DATA_API + "/richmenu/" + richMenuId + "/content"))
                .header("Authorization", bearer())
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image))
                .build());
        requireSuccess(response, "upload rich menu image");
    }

    private void setDefault(String richMenuId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(API + "/user/all/richmenu/" + richMenuId))
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        requireSuccess(response, "set default rich menu");
    }

    private void deleteLegacyMenus(String keepId) {
        try {
            for (JsonNode menu : listMenus()) {
                String id = menu.path("richMenuId").asText();
                String name = menu.path("name").asText();
                if (!keepId.equals(id) && name.startsWith("Benly Main")) {
                    deleteMenu(id);
                }
            }
        } catch (Exception e) {
            log.info("Legacy rich menus could not be cleaned up: {}", e.getMessage());
        }
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
