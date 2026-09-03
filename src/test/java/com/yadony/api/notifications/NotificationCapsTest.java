package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationCapsTest {

    @Test
    void capsMatchTheMeasuredRendering() {
        // Mesuré au rendu réel (Plus Jakarta Sans, colonne 268 pt) : 29 caractères de
        // titre en gras, 72 à 80 de corps sur deux lignes. Voir catalogue-gabarits.md.
        assertThat(NotificationCaps.TITLE_MAX).isEqualTo(28);
        assertThat(NotificationCaps.BODY_MAX).isEqualTo(72);
        assertThat(NotificationCaps.DISPLAY_NAME_MAX).isEqualTo(16);
    }

    @Test
    void truncateAtWord_keepsShortTextUntouched() {
        assertThat(NotificationCaps.truncateAtWord("Paiement reçu", 72)).isEqualTo("Paiement reçu");
        assertThat(NotificationCaps.truncateAtWord(null, 72)).isNull();
    }

    @Test
    void truncateAtWord_cutsOnAWordBoundaryWithEllipsis() {
        String text = "Bonjour, je serai à Roissy vendredi vers 18h, est-ce que vous pouvez me confirmer le point de remise";
        String cut = NotificationCaps.truncateAtWord(text, 72);
        assertThat(cut).endsWith("…");
        assertThat(cut.length()).isLessThanOrEqualTo(72);
        assertThat(cut).isEqualTo("Bonjour, je serai à Roissy vendredi vers 18h, est-ce que vous pouvez me…");
    }

    @Test
    void truncateAtWord_fallsBackToHardCutWhenNoSpaceFits() {
        String cut = NotificationCaps.truncateAtWord("abcdefghijklmnopqrstuvwxyz", 10);
        assertThat(cut).isEqualTo("abcdefghi…");
    }

    @Test
    void truncateAtWord_dropsTrailingPunctuationBeforeEllipsis() {
        assertThat(NotificationCaps.truncateAtWord("Un mot, puis un autre mot, et encore", 26))
                .isEqualTo("Un mot, puis un autre mot…");
    }

    @Test
    void shortDisplayName_reducesToFirstNameAndInitial() {
        assertThat(NotificationCaps.shortDisplayName("Mohammed Abdoulaye Diallo")).isEqualTo("Mohammed A.");
        assertThat(NotificationCaps.shortDisplayName("Fatou Diallo")).isEqualTo("Fatou D.");
        assertThat(NotificationCaps.shortDisplayName("Karim")).isEqualTo("Karim");
        assertThat(NotificationCaps.shortDisplayName("  Amadou   Sy ")).isEqualTo("Amadou S.");
    }

    @Test
    void shortDisplayName_neverExceedsTheNameCap() {
        assertThat(NotificationCaps.shortDisplayName("Kouassi-Konan Ahouansou Ahmadou-Tidiane"))
                .isEqualTo("Kouassi-Konan A.");  // 16, tient pile
        String name = NotificationCaps.shortDisplayName("Abdoulaye-Kouassi Konan");
        assertThat(name.length()).isLessThanOrEqualTo(NotificationCaps.DISPLAY_NAME_MAX);
        assertThat(name).endsWith("…");
        // Déjà réduit par UserEntity.publicDisplayName : la réduction est idempotente.
        assertThat(NotificationCaps.shortDisplayName("Mohammed A.")).isEqualTo("Mohammed A.");
        // Pas de repli ici : UserEntity.publicDisplayName() est la source unique du nom.
        assertThat(NotificationCaps.shortDisplayName(null)).isNull();
        assertThat(NotificationCaps.shortDisplayName("   ")).isEqualTo("   ");
    }

    @Test
    void policy_strictRejectsAnOverflow() {
        var policy = new NotificationCapsPolicy(NotificationCapsPolicy.Mode.STRICT);
        assertThatThrownBy(() -> policy.check("BID_CREATED", "Un titre beaucoup trop long pour tenir", "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title")
                .hasMessageContaining("BID_CREATED");
        assertThatThrownBy(() -> policy.check("BID_CREATED", "ok",
                "Un corps qui dépasse largement les soixante-douze caractères autorisés par le contrat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void policy_strictAcceptsTextsWithinCaps() {
        var policy = new NotificationCapsPolicy(NotificationCapsPolicy.Mode.STRICT);
        policy.check("BID_CREATED", "Nouvelle offre reçue", "Karim T. propose 45 EUR, Paris vers Dakar.");
        policy.check("BID_CREATED", null, null);
    }

    @Test
    void policy_warnAndOffLetOverflowsThrough() {
        new NotificationCapsPolicy(NotificationCapsPolicy.Mode.WARN)
                .check("X", "Un titre beaucoup trop long pour tenir", "ok");
        new NotificationCapsPolicy(NotificationCapsPolicy.Mode.OFF)
                .check("X", "Un titre beaucoup trop long pour tenir", "ok");
    }
}
