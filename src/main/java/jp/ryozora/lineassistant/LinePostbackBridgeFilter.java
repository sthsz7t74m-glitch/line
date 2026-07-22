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
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LinePostbackBridgeFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final LineProperties props;

    public LinePostbackBridgeFilter(ObjectMapper mapper, LineProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod());
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

        byte[] converted = convertPostbacks(original);
        String signature = sign(converted);
        filterChain.doFilter(new BodyRequest(request, converted, signature), response);
    }

    private byte[] convertPostbacks(byte[] body) throws IOException {
        JsonNode root = mapper.readTree(body);
        boolean changed = false;
        for (JsonNode eventNode : root.path("events")) {
            if (!(eventNode instanceof ObjectNode event)) continue;
            if (!"postback".equals(event.path("type").asText())) continue;
            String command = parseCommand(event.path("postback").path("data").asText());
            if (command == null || command.isBlank()) continue;

            event.put("type", "message");
            ObjectNode message = mapper.createObjectNode();
            message.put("id", "postback-bridge");
            message.put("type", "text");
            message.put("text", command);
            event.set("message", message);
            event.remove("postback");
            changed = true;
        }
        return changed ? mapper.writeValueAsBytes(root) : body;
    }

    private String parseCommand(String data) {
        if (data == null || data.isBlank()) return null;
        for (String part : data.split("&")) {
            int separator = part.indexOf('=');
            if (separator < 0) continue;
            String key = decode(part.substring(0, separator));
            if (!key.equals("cmd")) continue;
            return decode(part.substring(separator + 1));
        }
        return null;
    }

    private String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
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
            throw new IllegalStateException("LINE postback signature generation failed", e);
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
