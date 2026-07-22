package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RichMenuV7SetupService {
    private static final String MENU_NAME = "Benly Main v7 compact";
    private static final String API = "https://api.line.me/v2/bot";
    private static final String DATA_API = "https://api-data.line.me/v2/bot";
    private static final int WIDTH = 2500;
    private static final int HEIGHT = 1686;

    private final LineProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public RichMenuV7SetupService(LineProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void setup() {
        if (props.channelAccessToken() == null || props.channelAccessToken().isBlank()) return;
        try {
            Thread.sleep(1500);
            String existing = findMenu();
            if (existing != null && hasImage(existing)) {
                activate(existing);
                return;
            }
            String id = createMenu();
            upload(id, generateImage());
            activate(id);
            deleteOldMenus(id);
        } catch (Exception ignored) {
            // Existing menu remains available when refresh fails.
        }
    }

    private String createMenu() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Map.of("width", WIDTH, "height", HEIGHT));
        body.put("selected", true);
        body.put("name", MENU_NAME);
        body.put("chatBarText", "ベンリーメニュー");
        body.put("areas", areas());
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(API + "/richmenu"))
                .header("Authorization", bearer())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build());
        require2xx(response.statusCode(), response.body());
        String id = mapper.readTree(response.body()).path("richMenuId").asText();
        if (id.isBlank()) throw new IllegalStateException("richMenuId missing");
        return id;
    }

    private List<Map<String, Object>> areas() {
        List<Map<String, Object>> areas = new ArrayList<>();
        areas.add(area(0, 0, 833, 843, "今日・予定", "予定メニュー"));
        areas.add(area(833, 0, 833, 843, "メモ・タスク", "記録メニュー"));
        areas.add(area(1666, 0, 834, 843, "お金・買い物", "お金メニュー"));
        areas.add(area(0, 843, 833, 843, "習慣・成長", "成長メニュー"));
        areas.add(area(833, 843, 833, 843, "通知", "通知設定"));
        areas.add(area(1666, 843, 834, 843, "ホーム", "ホーム"));
        return areas;
    }

    private Map<String, Object> area(int x, int y, int width, int height, String label, String text) {
        return Map.of(
                "bounds", Map.of("x", x, "y", y, "width", width, "height", height),
                "action", Map.of("type", "message", "label", label, "text", text)
        );
    }

    private byte[] generateImage() throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(247, 249, 253));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            String[] titles = {"今日・予定", "メモ・タスク", "お金・買い物", "習慣・成長", "通知", "ホーム"};
            String[] subs = {"予定と天気", "メモとやること", "支出と買い物", "習慣と成長", "お知らせ設定", "メイン画面"};
            Color[] backgrounds = {
                    new Color(221, 235, 255), new Color(221, 245, 238), new Color(255, 240, 217),
                    new Color(238, 229, 255), new Color(230, 240, 255), new Color(235, 239, 246)
            };
            Color[] accents = {
                    new Color(46, 111, 196), new Color(46, 155, 107), new Color(216, 137, 22),
                    new Color(121, 87, 199), new Color(86, 126, 199), new Color(79, 96, 119)
            };
            String font = japaneseFont();
            Font titleFont = new Font(font, Font.BOLD, 84);
            Font subFont = new Font(font, Font.PLAIN, 42);
            int cellWidth = WIDTH / 3;
            int cellHeight = HEIGHT / 2;

            for (int i = 0; i < 6; i++) {
                int col = i % 3;
                int row = i / 3;
                int x = col * cellWidth;
                int y = row * cellHeight;
                int width = col == 2 ? WIDTH - x : cellWidth;

                g.setColor(new Color(215, 221, 232));
                g.fillRoundRect(x + 38, y + 44, width - 76, cellHeight - 80, 64, 64);
                g.setColor(backgrounds[i]);
                g.fillRoundRect(x + 28, y + 28, width - 56, cellHeight - 80, 64, 64);
                g.setColor(accents[i]);
                g.fillRoundRect(x + 28, y + 28, width - 56, 44, 64, 64);
                g.fillRect(x + 28, y + 50, width - 56, 22);

                g.setColor(accents[i]);
                drawCentered(g, icon(i), new Font(font, Font.BOLD, 150), x, y + 310, width);
                drawCentered(g, titles[i], titleFont, x, y + 500, width);
                g.setColor(new Color(84, 96, 116));
                drawCentered(g, subs[i], subFont, x, y + 610, width);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        if (bytes.length > 1024 * 1024) throw new IllegalStateException("rich menu image too large");
        return bytes;
    }

    private String icon(int index) {
        return switch (index) {
            case 0 -> "▣";
            case 1 -> "✓";
            case 2 -> "¥";
            case 3 -> "♧";
            case 4 -> "●";
            default -> "⌂";
        };
    }

    private void drawCentered(Graphics2D g, String value, Font font, int x, int baseline, int width) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(value, x + Math.max(0, (width - fm.stringWidth(value)) / 2), baseline);
    }

    private String japaneseFont() {
        String sample = "今日予定メモタスクお金買い物習慣成長通知ホーム";
        for (String candidate : List.of("Noto Sans CJK JP", "Noto Sans JP", "IPAexGothic", Font.SANS_SERIF)) {
            Font font = new Font(candidate, Font.BOLD, 72);
            if (font.canDisplayUpTo(sample) == -1) return font.getFamily(Locale.JAPANESE);
        }
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.JAPANESE)) {
            if (new Font(family, Font.BOLD, 72).canDisplayUpTo(sample) == -1) return family;
        }
        return Font.SANS_SERIF;
    }

    private void upload(String id, byte[] image) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(DATA_API + "/richmenu/" + id + "/content"))
                .header("Authorization", bearer())
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image)).build());
        require2xx(response.statusCode(), response.body());
    }

    private void activate(String id) throws Exception {
        postEmpty(URI.create(API + "/user/all/richmenu/" + id));
        if (props.ownerUserId() != null && !props.ownerUserId().isBlank()) {
            postEmpty(URI.create(API + "/user/" + props.ownerUserId() + "/richmenu/" + id));
        }
    }

    private void postEmpty(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", bearer());
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(0);
        connection.getOutputStream().close();
        int code = connection.getResponseCode();
        connection.disconnect();
        require2xx(code, "");
    }

    private String findMenu() throws Exception {
        for (JsonNode menu : listMenus()) {
            if (MENU_NAME.equals(menu.path("name").asText())) return menu.path("richMenuId").asText();
        }
        return null;
    }

    private JsonNode listMenus() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(API + "/richmenu/list"))
                .header("Authorization", bearer()).GET().build());
        require2xx(response.statusCode(), response.body());
        return mapper.readTree(response.body()).path("richmenus");
    }

    private boolean hasImage(String id) throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(DATA_API + "/richmenu/" + id + "/content"))
                .header("Authorization", bearer()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return response.statusCode() / 100 == 2 && response.body() != null && response.body().length > 8;
    }

    private void deleteOldMenus(String keepId) throws Exception {
        for (JsonNode menu : listMenus()) {
            String name = menu.path("name").asText();
            String id = menu.path("richMenuId").asText();
            if (!id.equals(keepId) && name.startsWith("Benly Main")) {
                send(HttpRequest.newBuilder(URI.create(API + "/richmenu/" + id))
                        .header("Authorization", bearer()).DELETE().build());
            }
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void require2xx(int status, String body) {
        if (status / 100 != 2) throw new IllegalStateException("LINE API HTTP " + status + ": " + body);
    }

    private String bearer() {
        return "Bearer " + props.channelAccessToken();
    }
}
