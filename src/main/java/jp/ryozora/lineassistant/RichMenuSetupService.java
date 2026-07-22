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
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
    static {
        // BufferedImage/ImageIO work without an X server. Setting this explicitly keeps
        // image generation predictable on headless hosts such as Render.
        System.setProperty("java.awt.headless", "true");
    }

    private static final Logger log = LoggerFactory.getLogger(RichMenuSetupService.class);
    private static final String MENU_NAME = "Benly Main v3";
    private static final String API = "https://api.line.me/v2/bot";
    private static final String DATA_API = "https://api-data.line.me/v2/bot";
    private static final int WIDTH = 2500;
    private static final int HEIGHT = 1686;

    private final LineProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final boolean autoSetup;

    private volatile String lastStatus = "not-started";
    private volatile String lastStage = "idle";
    private volatile String lastError = "";
    private volatile String lastMenuId = "";
    private volatile int lastImageBytes;

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
            lastStage = "startup";
            log.info("Benly rich menu setup is disabled");
            return;
        }
        if (tokenMissing()) {
            lastStatus = "token-missing";
            lastStage = "startup";
            log.warn("Benly rich menu setup skipped: LINE channel access token is missing");
            return;
        }
        try {
            String richMenuId = setupDefaultMenu();
            log.info("Benly rich menu is ready: {}", richMenuId);
        } catch (Exception e) {
            rememberFailure(e);
            log.warn("Benly rich menu setup failed at {}: {}", lastStage, lastError);
        }
    }

    public String status() {
        StringBuilder out = new StringBuilder()
                .append("状態：").append(lastStatus)
                .append("\n工程：").append(lastStage);
        if (!lastMenuId.isBlank()) out.append("\nID：").append(lastMenuId);
        if (lastImageBytes > 0) out.append("\n画像：").append(lastImageBytes).append(" bytes");
        if (!lastError.isBlank()) out.append("\n詳細：").append(lastError);
        return out.toString();
    }

    public synchronized String diagnosticStatus(String userId) {
        StringBuilder out = new StringBuilder(status());
        if (tokenMissing()) return out.toString();

        try {
            String defaultId = getDefaultMenuId();
            out.append("\nLINE既定：").append(defaultId == null ? "なし" : defaultId);
        } catch (Exception e) {
            out.append("\nLINE既定確認：").append(safeMessage(e));
        }

        if (userId != null && !userId.isBlank()) {
            try {
                String linkedId = getLinkedMenuId(userId);
                out.append("\nあなたへの紐付け：").append(linkedId == null ? "なし" : linkedId);
            } catch (Exception e) {
                out.append("\n個別紐付け確認：").append(safeMessage(e));
            }
        }
        return out.toString();
    }

    public synchronized String setupDefaultMenu() throws Exception {
        return setup(false, props.ownerUserId());
    }

    /**
     * Deletes stale Benly rich menus, creates a fresh one, sets it as the
     * default, and links it directly to the requesting LINE user.
     */
    public synchronized String recreateForUser(String userId) throws Exception {
        return setup(true, userId);
    }

    private String setup(boolean forceRecreate, String userId) throws Exception {
        if (tokenMissing()) {
            lastStatus = "token-missing";
            lastStage = "authentication";
            throw new IllegalStateException("LINE channel access token is missing");
        }

        lastStatus = "running";
        lastError = "";
        lastImageBytes = 0;

        try {
            if (forceRecreate) {
                lastStage = "cleanup";
                unlinkUserQuietly(userId);
                deleteAllBenlyMenus();
            }

            lastStage = "menu-list";
            String existing = findExistingMenu();
            if (existing != null) {
                lastStage = "image-check";
                if (hasImage(existing)) {
                    activate(existing, userId);
                    rememberReady(existing);
                    return existing;
                }
                deleteMenu(existing);
            }

            lastStage = "menu-validate";
            Map<String, Object> menuObject = menuObject();
            validateMenu(menuObject);

            lastStage = "menu-create";
            String richMenuId = createMenu(menuObject);
            lastMenuId = richMenuId;
            try {
                lastStage = "image-generate";
                byte[] image = generateMenuImage();
                lastImageBytes = image.length;
                log.info("Generated rich menu PNG: {} bytes", image.length);

                lastStage = "image-upload";
                uploadImage(richMenuId, image);

                lastStage = "image-verify";
                if (!hasImage(richMenuId)) {
                    throw new IllegalStateException("LINE accepted no readable image for the rich menu");
                }

                activate(richMenuId, userId);

                lastStage = "legacy-cleanup";
                deleteLegacyMenus(richMenuId);

                rememberReady(richMenuId);
                return richMenuId;
            } catch (Exception e) {
                deleteMenu(richMenuId);
                throw e;
            }
        } catch (Exception e) {
            rememberFailure(e);
            throw e;
        }
    }

    private void activate(String richMenuId, String userId) throws Exception {
        lastStage = "default-link";
        setDefault(richMenuId);

        if (userId != null && !userId.isBlank()) {
            lastStage = "user-link";
            linkUser(userId, richMenuId);
        }

        lastStage = "link-verify";
        String defaultId = getDefaultMenuId();
        if (!richMenuId.equals(defaultId)) {
            throw new IllegalStateException("default rich menu verification failed");
        }
        if (userId != null && !userId.isBlank()) {
            String linkedId = getLinkedMenuId(userId);
            if (!richMenuId.equals(linkedId)) {
                throw new IllegalStateException("per-user rich menu verification failed");
            }
        }
    }

    private void rememberReady(String richMenuId) {
        lastStatus = "ready";
        lastStage = "complete";
        lastError = "";
        lastMenuId = richMenuId;
    }

    private void rememberFailure(Exception e) {
        lastStatus = "failed";
        lastError = safeMessage(e);
    }

    private String safeMessage(Throwable error) {
        String value = error == null ? "unknown error" : error.getMessage();
        if (value == null || value.isBlank()) {
            value = error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        value = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").strip();
        if (value.length() > 500) value = value.substring(0, 500);
        return value;
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
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(URI.create(API + "/richmenu/list"))
                .header("Authorization", bearer())
                .GET().build());
        requireSuccess(response, "list rich menus");
        return mapper.readTree(response.body()).path("richmenus");
    }

    private Map<String, Object> menuObject() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Map.of("width", WIDTH, "height", HEIGHT));
        body.put("selected", true);
        body.put("name", MENU_NAME);
        body.put("chatBarText", "ベンリーを開く");
        body.put("areas", areas());
        return body;
    }

    private void validateMenu(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(URI.create(API + "/richmenu/validate"))
                .header("Authorization", bearer())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build());
        requireSuccess(response, "validate rich menu");
    }

    private String createMenu(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(URI.create(API + "/richmenu"))
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
            Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 91);
            Font subFont = new Font(Font.SANS_SERIF, Font.BOLD, 58);

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
        if (!isPng(bytes)) {
            throw new IllegalStateException("Generated rich menu image is not a PNG");
        }
        if (bytes.length > 1024 * 1024) {
            throw new IllegalStateException("Generated rich menu image exceeds 1 MB: " + bytes.length + " bytes");
        }
        return bytes;
    }

    private boolean isPng(byte[] bytes) {
        return bytes != null && bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47
                && bytes[4] == 0x0D && bytes[5] == 0x0A
                && bytes[6] == 0x1A && bytes[7] == 0x0A;
    }

    private void drawCentered(Graphics2D g, String text, int x, int baseline, int width) {
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        g.drawString(text, x + Math.max(0, (width - textWidth) / 2), baseline);
    }

    private void uploadImage(String richMenuId, byte[] image) throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(
                        URI.create(DATA_API + "/richmenu/" + richMenuId + "/content"))
                .header("Authorization", bearer())
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image))
                .build());
        requireSuccess(response, "upload rich menu image");
    }

    private boolean hasImage(String richMenuId) throws Exception {
        HttpResponse<byte[]> response = sendBytes(HttpRequest.newBuilder(
                        URI.create(DATA_API + "/richmenu/" + richMenuId + "/content"))
                .header("Authorization", bearer())
                .GET().build());
        if (response.statusCode() == 404) return false;
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("get rich menu image failed: HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        return body != null && body.length > 8;
    }

    private void setDefault(String richMenuId) throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(
                        URI.create(API + "/user/all/richmenu/" + richMenuId))
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        requireSuccess(response, "set default rich menu");
    }

    private String getDefaultMenuId() throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(
                        URI.create(API + "/user/all/richmenu"))
                .header("Authorization", bearer())
                .GET().build());
        if (response.statusCode() == 404) return null;
        requireSuccess(response, "get default rich menu");
        String id = mapper.readTree(response.body()).path("richMenuId").asText();
        return id.isBlank() ? null : id;
    }

    private void linkUser(String userId, String richMenuId) throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(
                        URI.create(API + "/user/" + userId + "/richmenu/" + richMenuId))
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        requireSuccess(response, "link rich menu to user");
    }

    private String getLinkedMenuId(String userId) throws Exception {
        HttpResponse<String> response = sendText(HttpRequest.newBuilder(
                        URI.create(API + "/user/" + userId + "/richmenu"))
                .header("Authorization", bearer())
                .GET().build());
        if (response.statusCode() == 404) return null;
        requireSuccess(response, "get user rich menu");
        String id = mapper.readTree(response.body()).path("richMenuId").asText();
        return id.isBlank() ? null : id;
    }

    private void unlinkUserQuietly(String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            sendText(HttpRequest.newBuilder(URI.create(API + "/user/" + userId + "/richmenu"))
                    .header("Authorization", bearer())
                    .DELETE().build());
        } catch (Exception ignored) {
            // Best-effort cleanup before a forced recreation.
        }
    }

    private void deleteAllBenlyMenus() throws Exception {
        for (JsonNode menu : listMenus()) {
            String id = menu.path("richMenuId").asText();
            String name = menu.path("name").asText();
            if (name.startsWith("Benly Main")) deleteMenu(id);
        }
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
            log.info("Legacy rich menus could not be cleaned up: {}", safeMessage(e));
        }
    }

    private void deleteMenu(String richMenuId) {
        if (richMenuId == null || richMenuId.isBlank()) return;
        try {
            sendText(HttpRequest.newBuilder(URI.create(API + "/richmenu/" + richMenuId))
                    .header("Authorization", bearer())
                    .DELETE().build());
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

    private HttpResponse<byte[]> sendBytes(HttpRequest request) throws Exception {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LINE rich menu request interrupted", e);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            body = body.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").strip();
            if (body.length() > 400) body = body.substring(0, 400);
            throw new IllegalStateException(operation + " failed: HTTP " + response.statusCode()
                    + (body.isBlank() ? "" : " " + body));
        }
    }

    private String bearer() {
        return "Bearer " + props.channelAccessToken();
    }

    private boolean tokenMissing() {
        return props.channelAccessToken() == null || props.channelAccessToken().isBlank();
    }
}
