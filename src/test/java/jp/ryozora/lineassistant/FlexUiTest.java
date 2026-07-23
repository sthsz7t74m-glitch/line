package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlexUiTest {

    @Test
    void buildsTextButtonAndCardComponents() {
        Map<String, Object> text = FlexUi.text("テスト", "sm", "bold", "#243B53");
        Map<String, Object> button = FlexUi.button("ホーム", "ホーム", "#667EA8");
        Map<String, Object> card = FlexUi.card("#F8FAFC", "10px", "xs", List.of(text, button));

        assertThat(text)
                .containsEntry("type", "text")
                .containsEntry("text", "テスト")
                .containsEntry("wrap", true);
        assertThat(button)
                .containsEntry("type", "button")
                .containsEntry("style", "primary")
                .containsEntry("color", "#667EA8");
        assertThat(card)
                .containsEntry("type", "box")
                .containsEntry("layout", "vertical")
                .containsEntry("cornerRadius", "12px");
    }

    @Test
    void buildsFlexMessageAndQuickReplyAction() {
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#EEF2F7", "12px", "xs", List.of(
                        FlexUi.text("見出し", "lg", "bold", "#526D82")
                )),
                FlexUi.vertical("#FFFFFF", "12px", "sm", List.of(
                        FlexUi.text("本文", "sm", "regular", "#526D82")
                ))
        );
        Map<String, Object> message = FlexUi.flexMessage("テスト画面", bubble);
        Map<String, Object> quick = FlexUi.quick("ホーム", "ホーム");

        assertThat(message)
                .containsEntry("type", "flex")
                .containsEntry("altText", "テスト画面")
                .containsEntry("contents", bubble);
        assertThat(quick).containsEntry("type", "action");
    }
}
