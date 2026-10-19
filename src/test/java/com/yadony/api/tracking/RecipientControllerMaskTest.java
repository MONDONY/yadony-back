package com.yadony.api.tracking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Numéro du destinataire sur la page publique : masqué, et seulement s'il est international. */
class RecipientControllerMaskTest {

    @Test
    void internationalNumber_isMasked() {
        assertThat(RecipientController.maskedRecipientPhone("+225 07 01 02 03 34")).isEqualTo("+225 •••• 34");
        assertThat(RecipientController.maskedRecipientPhone("00221-77-123-45-67")).isEqualTo("+221 •••• 67");
        assertThat(RecipientController.maskedRecipientPhone("(+223) 70.12.34.56")).isEqualTo("+223 •••• 56");
    }

    @Test
    void localOrMissingNumber_isNotShown() {
        assertThat(RecipientController.maskedRecipientPhone(null)).isNull();
        assertThat(RecipientController.maskedRecipientPhone("07 01 02 03 34")).isNull();
        assertThat(RecipientController.maskedRecipientPhone("+12")).isNull();
    }
}
