package com.yadony.api.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupportMessagePreviewTest {

    @Test
    void noMessage_noPreview() {
        assertThat(SupportMessagePreview.of(null, "Pièce jointe")).isNull();
    }

    @Test
    void emptyOrNullContent_takesTheAttachmentLabel() {
        assertThat(SupportMessagePreview.of(message(null), "Pièce jointe")).isEqualTo("Pièce jointe");
        assertThat(SupportMessagePreview.of(message(" \n "), "Attachment")).isEqualTo("Attachment");
    }

    @Test
    void shortText_isFlattenedOnOneLine() {
        assertThat(SupportMessagePreview.of(message("  Bonjour,\n\n  merci  "), "x")).isEqualTo("Bonjour, merci");
    }

    @Test
    void longText_isCutAtAWordWithinTheCap() {
        String text = "Bonjour, nous avons bien recu votre demande et nous revenons vers vous tres vite avec une reponse.";
        String preview = SupportMessagePreview.of(message(text), "x");
        assertThat(preview).isEqualTo("Bonjour, nous avons bien recu votre demande et nous revenons vers vous tres…")
                .hasSizeLessThanOrEqualTo(SupportMessagePreview.MAX_LENGTH);
    }

    @Test
    void cutFallingOnPunctuation_dropsTheTrailingPunctuation() {
        String text = "a".repeat(78) + ", suite du message";
        assertThat(SupportMessagePreview.truncate(text)).isEqualTo("a".repeat(78) + "…");
    }

    @Test
    void textWithoutSpaces_isCutHard() {
        String preview = SupportMessagePreview.truncate("x".repeat(120));
        assertThat(preview).hasSize(SupportMessagePreview.MAX_LENGTH).endsWith("…");
    }

    private static SupportMessageEntity message(String content) {
        SupportMessageEntity m = new SupportMessageEntity();
        m.setContent(content);
        return m;
    }
}
