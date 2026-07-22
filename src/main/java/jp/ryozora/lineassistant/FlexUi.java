package jp.ryozora.lineassistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlexUi {
    private FlexUi() {}

    public static Map<String, Object> text(String value, String size, String weight, String color) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", value);
        text.put("size", size);
        text.put("weight", weight);
        text.put("color", color);
        text.put("wrap", true);
        return text;
    }

    public static Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "primary");
        button.put("height", "sm");
        button.put("color", color);
        button.put("adjustMode", "shrink-to-fit");
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    public static Map<String, Object> vertical(String background, String padding, String spacing,
                                               List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("paddingAll", padding);
        box.put("spacing", spacing);
        box.put("contents", contents);
        return box;
    }

    public static Map<String, Object> horizontal(List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "horizontal");
        box.put("spacing", "sm");
        box.put("contents", contents);
        return box;
    }

    public static Map<String, Object> bubble(Map<String, Object> header, Map<String, Object> body) {
        return Map.of("type", "bubble", "size", "mega", "header", header, "body", body);
    }

    public static Map<String, Object> flexMessage(String altText, Map<String, Object> bubble) {
        return Map.of("type", "flex", "altText", altText, "contents", bubble);
    }
}
