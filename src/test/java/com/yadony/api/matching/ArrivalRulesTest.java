package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FLUTTER-4E : date d'arrivée différente du départ. */
class ArrivalRulesTest {

    private static final LocalDate DEP = LocalDate.of(2026, 10, 4);
    private static final LocalTime T22 = LocalTime.of(22, 0);
    private static final LocalTime T0630 = LocalTime.of(6, 30);

    @Test
    @DisplayName("vol de nuit : arrivée le lendemain à 06:30 → accepté")
    void overnight_nextDay_ok() {
        assertThatCode(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(1), T0630))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sans date d'arrivée (ancienne app) : jamais bloqué")
    void noArrivalDate_tolerated() {
        assertThatCode(() -> ArrivalRules.validate(DEP, T22, null, T0630))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("arrivée avant le départ → 422 arrival-before-departure")
    void arrivalBeforeDeparture_rejected() {
        assertThatThrownBy(() -> ArrivalRules.validate(DEP, T22, DEP.minusDays(1), T0630))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("arrival-before-departure"));
    }

    @Test
    @DisplayName("plus de 3 jours après le départ → 422 arrival-too-far")
    void arrivalTooFar_rejected() {
        assertThatThrownBy(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(4), T0630))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("arrival-too-far"));
    }

    @Test
    @DisplayName("même jour, heure d'arrivée avant le départ → 422")
    void sameDayArrivalTimeBeforeDeparture_rejected() {
        assertThatThrownBy(() -> ArrivalRules.validate(DEP, T22, DEP, T0630))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("arrival-time-before-departure"));
    }

    @Test
    @DisplayName("jour d'arrivée effectif : date d'arrivée, sinon jour du départ")
    void effectiveArrivalDate() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(DEP);
        assertThat(ArrivalRules.effectiveArrivalDate(a)).isEqualTo(DEP);
        a.setArrivalDate(DEP.plusDays(1));
        assertThat(ArrivalRules.effectiveArrivalDate(a)).isEqualTo(DEP.plusDays(1));
    }

    @Test
    @DisplayName("code régénéré après la fenêtre prévue : valable encore 24 h (FLUTTER-BA)")
    void renewedCode_afterPlannedWindow_validForAnotherDay() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(10));
        a.setArrivalDate(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(9));

        assertThat(ArrivalRules.renewedPickupCodeExpiry(a))
                .isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(23));
    }

    @Test
    @DisplayName("code régénéré avant l'arrivée : garde l'expiration prévue du trajet")
    void renewedCode_beforeArrival_keepsPlannedExpiry() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(5));
        a.setArrivalDate(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(5));

        assertThat(ArrivalRules.renewedPickupCodeExpiry(a)).isEqualTo(ArrivalRules.pickupCodeExpiry(a));
    }
}
