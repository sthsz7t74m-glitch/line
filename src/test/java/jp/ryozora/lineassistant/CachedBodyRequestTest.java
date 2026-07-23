package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CachedBodyRequestTest {

    @Test
    void bodyCanBeReadMoreThanOnce() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        CachedBodyRequest request = new CachedBodyRequest(new MockHttpServletRequest(), body);

        String first = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String second = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String fromReader = request.getReader().readLine();

        assertThat(first).isEqualTo("{\"events\":[]}");
        assertThat(second).isEqualTo(first);
        assertThat(fromReader).isEqualTo(first);
        assertThat(request.getContentLength()).isEqualTo(body.length);
        assertThat(request.getContentLengthLong()).isEqualTo(body.length);
    }

    @Test
    void constructorDefensivelyCopiesBody() throws Exception {
        byte[] body = "original".getBytes(StandardCharsets.UTF_8);
        CachedBodyRequest request = new CachedBodyRequest(new MockHttpServletRequest(), body);
        body[0] = 'X';

        assertThat(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("original");
    }
}
