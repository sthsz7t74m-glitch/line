package jp.ryozora.lineassistant;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice
public class AppVersionResponseAdvice implements ResponseBodyAdvice<Object> {
    public static final String VERSION = "0.12.0";

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return Map.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof Map<?, ?> source)) return body;
        if (!"/".equals(request.getURI().getPath()) || !source.containsKey("app")) return body;
        Map<Object, Object> result = new LinkedHashMap<>(source);
        result.put("version", VERSION);
        result.put("conversationContext", "enabled");
        result.put("postback", "enabled");
        result.put("customNotificationTimes", "enabled");
        return result;
    }
}
