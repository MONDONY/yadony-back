package com.yadony.api.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Rendu réel (MessageFormat) des refus de prise en charge d'alerte : les apostrophes ne doivent pas être avalées. */
class AlertClaimMessagesTest {

    private final MessageSource source = MessageSourceFactory.create();

    @Test
    void claimNotYours_fr() {
        assertThat(source.getMessage("problem.admin.alert-claim-not-yours.detail", null, Locale.FRENCH))
                .isEqualTo("Seul l'administrateur qui a pris l'alerte en charge peut la libérer");
    }

    @Test
    void claimNotYours_en() {
        assertThat(source.getMessage("problem.admin.alert-claim-not-yours.detail", null, Locale.ENGLISH))
                .isEqualTo("Only the administrator who took the alert can release it");
    }

    @Test
    void alreadyClaimed_fr_en() {
        assertThat(source.getMessage("problem.admin.alert-already-claimed.detail", null, Locale.FRENCH))
                .isEqualTo("Cette alerte est déjà prise en charge par un autre administrateur");
        assertThat(source.getMessage("problem.admin.alert-already-claimed.detail", null, Locale.ENGLISH))
                .isEqualTo("This alert is already handled by another administrator");
    }
}
