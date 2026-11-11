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
        assertThatCode(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(1), T0630, TransportMode.PLANE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sans date d'arrivée (ancienne app) : jamais bloqué")
    void noArrivalDate_tolerated() {
        assertThatCode(() -> ArrivalRules.validate(DEP, T22, null, T0630, TransportMode.PLANE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("arrivée avant le départ → 422 arrival-before-departure")
    void arrivalBeforeDeparture_rejected() {
        assertThatThrownBy(() -> ArrivalRules.validate(DEP, T22, DEP.minusDays(1), T0630, TransportMode.PLANE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("arrival-before-departure"));
    }

    @Test
    @DisplayName("plus de 3 jours après le départ → 422 arrival-too-far")
    void arrivalTooFar_rejected() {
        assertThatThrownBy(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(4), T0630, TransportMode.PLANE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("arrival-too-far"));
    }

    @Test
    @DisplayName("durée maximale par mode : avion/train/autre 3, voiture/bus 14, bateau 120, null 3")
    void maxDays_perMode() {
        assertThat(ArrivalRules.maxDaysAfterDeparture(TransportMode.PLANE)).isEqualTo(3);
        assertThat(ArrivalRules.maxDaysAfterDeparture(TransportMode.TRAIN)).isEqualTo(3);
        assertThat(ArrivalRules.maxDaysAfterDeparture(TransportMode.OTHER)).isEqualTo(3);
        assertThat(ArrivalRules.maxDaysAfterDeparture(TransportMode.CAR)).isEqualTo(14);
        assertThat(ArrivalRules.maxDaysAfterDeparture(TransportMode.BUS)).isEqualTo(14);
        assertThat(ArrivalRules.maxDaysAfterDeparture(TransportMode.BOAT)).isEqualTo(120);
        assertThat(ArrivalRules.maxDaysAfterDeparture(null)).isEqualTo(3);
    }

    @Test
    @DisplayName("avion : arrivée à J+4 → 422 arrival-too-far")
    void plane_arrivalAtDay4_rejected() {
        assertTooFar(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(4), null, TransportMode.PLANE));
    }

    @Test
    @DisplayName("voiture : arrivée à J+14 → accepté")
    void car_arrivalAtDay14_accepted() {
        assertThatCode(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(14), null, TransportMode.CAR))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("voiture : arrivée à J+15 → 422 arrival-too-far")
    void car_arrivalAtDay15_rejected() {
        assertTooFar(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(15), null, TransportMode.CAR));
    }

    @Test
    @DisplayName("bateau : arrivée à J+120 → accepté")
    void boat_arrivalAtDay120_accepted() {
        assertThatCode(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(120), null, TransportMode.BOAT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("bateau : arrivée à J+121 → 422 arrival-too-far")
    void boat_arrivalAtDay121_rejected() {
        assertTooFar(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(121), null, TransportMode.BOAT));
    }

    @Test
    @DisplayName("le message d'erreur cite la durée du mode, pas 3 jours en dur")
    void tooFarMessage_citesModeLimit() {
        assertThatThrownBy(() -> ArrivalRules.validate(DEP, T22, DEP.plusDays(15), null, TransportMode.CAR))
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getMessage()).contains("14"));
    }

    private static void assertTooFar(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("arrival-too-far"));
    }

    @Test
    @DisplayName("même jour, heure d'arrivée avant le départ → 422")
    void sameDayArrivalTimeBeforeDeparture_rejected() {
        assertThatThrownBy(() -> ArrivalRules.validate(DEP, T22, DEP, T0630, TransportMode.PLANE))
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
