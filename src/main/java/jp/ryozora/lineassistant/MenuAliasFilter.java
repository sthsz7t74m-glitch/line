package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MenuAliasFilter extends OncePerRequestFilter {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("今日・予定", "予定メニュー"),
            Map.entry("今日と予定", "予定メニュー"),
            Map.entry("予定", "予定メニュー"),
            Map.entry("メモ・タスク", "記録メニュー"),
            Map.entry("メモとタスク", "記録メニュー"),
            Map.entry("記録", "記録メニュー"),
            Map.entry("お金・買い物", "お金メニュー"),
            Map.entry("お金と買い物", "お金メニュー"),
            Map.entry("お金", "お金メニュー"),
            Map.entry("習慣・成長", "成長メニュー"),
            Map.entry("習慣と成長", "成長メニュー"),
            Map.entry("成長", "成長メニュー")
    );

    private final ObjectMapper mapper;
    private final LineProperties props;

    public MenuAliasFilter(ObjectMapper mapper, LineProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] original = request.getInputStream().readAllBytes();
        String received = request.getHeader("x-line-signature");
        if (!validSignature(original, received)) {
            filterChain.doFilter(new BodyRequest(request, original, received), response);
            return;
        }

        byte[] converted = convertAliases(original);
        String signature = sign(converted);
        filterChain.doFilter(new BodyRequest(request, converted, signature), response);
    }

    private byte[] convertAliases(byte[] body) throws IOException {
        JsonNode root = mapper.readTree(body);
        boolean changed = false;
        for (JsonNode eventNode : root.path("events")) {
            if (!(eventNode instanceof ObjectNode event)) continue;
            if (!"message".equals(event.path("type").asText())) continue;
            JsonNode messageNode = event.path("message");
            if (!(messageNode instanceof ObjectNode message)) continue;
            if (!"text".equals(message.path("type").asText())) continue;

            String raw = message.path("text").asText();
            String normalized = normalize(raw);
            String canonical = ALIASES.get(normalized);
            if (canonical == null) continue;

            message.put("text", canonical);
            changed = true;
        }
        return changed ? mapper.writeValueAsBytes(root) : body;
    }

    private String normalize(String value) {
        return value == null ? "" : value
                .replace('\u3000', ' ')
                .replace('〜', '・')
                .replace('～', '・')
                .replace(" ", "")
                .strip();
    }

    private boolean validSignature(byte[] body, String received) {
        if (received == null || props.channelSecret() == null || props.channelSecret().isBlank()) return false;
        String expected = sign(body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.channelSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("LINE menu alias signature generation failed", e);
        }
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final String signature;

        private BodyRequest(HttpServletRequest request, byte[] body, String signature) {
            super(request);
            this.body = body;
            this.signature = signature;
        }

        @Override
        public String getHeader(String name) {
            if ("x-line-signature".equalsIgnoreCase(name)) return signature;
            return super.getHeader(name);
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }
    }
}
