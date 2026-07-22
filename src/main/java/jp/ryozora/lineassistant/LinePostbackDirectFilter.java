package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LinePostbackDirectFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final LineProperties props;
    private final RichMenuPostbackHandler handler;

    public LinePostbackDirectFilter(ObjectMapper mapper, LineProperties props,
                                    RichMenuPostbackHandler handler) {
        this.mapper = mapper;
        this.props = props;
        this.handler = handler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String signature = request.getHeader("x-line-signature");

        if (!validSignature(body, signature)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        boolean containsPostback = false;
        for (JsonNode event : root.path("events")) {
            if (!"postback".equals(event.path("type").asText())) continue;
            containsPostback = true;

            String userId = event.path("source").path("userId").asText();
            if (!allowed(userId)) continue;
            String replyToken = event.path("replyToken").asText();
            String command = parseCommand(event.path("postback").path("data").asText());
            if (replyToken.isBlank() || !handler.supports(command)) continue;

            try {
                handler.handle(userId, replyToken, command);
            } catch (RuntimeException e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }
        }

        if (containsPostback) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // This request is an ordinary text-message webhook. Recreate a readable request body
        // for the existing controller instead of altering its contents or signature.
        filterChain.doFilter(new RepeatableBodyRequest(request, body), response);
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank()
                || props.ownerUserId().equals(userId);
    }

    private String parseCommand(String data) {
        if (data == null || data.isBlank()) return null;
        for (String part : data.split("&")) {
            int separator = part.indexOf('=');
            if (separator < 0) continue;
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            if (!"cmd".equals(key)) continue;
            return URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private boolean validSignature(byte[] body, String received) {
        if (received == null || props.channelSecret() == null || props.channelSecret().isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.channelSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    received.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("LINE signature verification failed", e);
        }
    }

    private static final class RepeatableBodyRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] body;

        private RepeatableBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
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
        public jakarta.servlet.ServletInputStream getInputStream() {
            java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener readListener) {}
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
