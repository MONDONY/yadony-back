package com.yadony.api.assistant.adminhelp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminHelpRedactorTest {

    @Test
    void masqueEmailTelephoneEtIdentifiant() {
        String out = AdminHelpRedactor.redact("Le colis de awa.diop@mail.com, +221 77 123 45 67, "
                + "id 3f2b8c1e-1234-4abc-9def-0123456789ab est en retard");
        assertThat(out).doesNotContain("awa.diop", "77 123", "3f2b8c1e")
                .contains("[email]", "[numéro]", "[identifiant]", "est en retard");
    }

    @Test
    void laisseLesPetitsNombresEtLeTexteVide() {
        assertThat(AdminHelpRedactor.redact("colis de 10 kg en 2026")).isEqualTo("colis de 10 kg en 2026");
        assertThat(AdminHelpRedactor.redact(null)).isEmpty();
    }
}
