package com.yadony.api.common.i18n;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizedDateTest {

    @Test
    void francais_premierDuMoisEn1er_autresJoursEnChiffres() {
        assertThat(new LocalizedDate(LocalDate.of(2026, 12, 1)).format(AppLanguage.FR)).isEqualTo("1er décembre 2026");
        assertThat(new LocalizedDate(LocalDate.of(2027, 1, 2)).format(AppLanguage.FR)).isEqualTo("2 janvier 2027");
        assertThat(new LocalizedDate(LocalDate.of(2027, 2, 21)).format(AppLanguage.FR)).isEqualTo("21 février 2027");
    }

    @Test
    void anglais_moisJourAnnee() {
        assertThat(new LocalizedDate(LocalDate.of(2026, 12, 1)).format(AppLanguage.EN)).isEqualTo("December 1, 2026");
        assertThat(new LocalizedDate(LocalDate.of(2027, 1, 15)).format(AppLanguage.EN)).isEqualTo("January 15, 2027");
    }

    @Test
    void messages_ecritLaDateDansSaLangue_etLaisseLesAutresArgumentsInchanges() {
        // « problem.monthly-trip-limit-reached.other.detail » : {0} = limite, {1} = date.
        Object[] args = {3, new LocalizedDate(LocalDate.of(2026, 12, 1))};
        assertThat(TestMessages.fr().get("problem.monthly-trip-limit-reached.other.detail", args))
                .contains("limite de 3 trajets").endsWith("1er décembre 2026.");
        assertThat(TestMessages.en().get("problem.monthly-trip-limit-reached.other.detail", args))
                .contains("limit of 3 trips").endsWith("December 1, 2026.");
    }
}
