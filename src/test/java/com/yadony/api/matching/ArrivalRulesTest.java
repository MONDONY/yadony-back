package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private static TripDelayEntity delayEndingOn(LocalDate max) {
        TripDelayEntity d = new TripDelayEntity();
        d.setNewArrivalDate(max.minusDays(2));
        d.setNewArrivalDateMax(max);
        return d;
    }

    @Test
    @DisplayName("code régénéré après un retard hors bateau : calé sur la fin de fourchette du retard (M1)")
    void renewedCode_afterNonBoatDelay_usesTheDelayedRangeEnd() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", java.util.UUID.randomUUID());
        a.setTransportMode(TransportMode.CAR);
        a.setDepartureDate(today);
        a.setArrivalDate(today.plusDays(3));
        TripDelayRepository repository = org.mockito.Mockito.mock(TripDelayRepository.class);
        org.mockito.Mockito.when(repository.findFirstByAnnouncementIdOrderByCreatedAtDesc(a.getId()))
                .thenReturn(java.util.Optional.of(delayEndingOn(today.plusDays(10))));

        // Sans le retard, l'expiration tombe vers J+6 ; avec lui, 72 h après le début de J+10.
        assertThat(ArrivalRules.renewedPickupCodeExpiry(a)).isEqualTo(today.plusDays(6).atStartOfDay());
        assertThat(ArrivalRules.renewedPickupCodeExpiry(a, repository)).isEqualTo(today.plusDays(13).atStartOfDay());
    }

    @Test
    @DisplayName("code régénéré : sans dépôt, sans retard, bateau ou retard plus court, la règle de l'annonce prévaut")
    void renewedCode_withoutUsableDelay_keepsTheAnnouncementRule() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", java.util.UUID.randomUUID());
        a.setTransportMode(TransportMode.CAR);
        a.setDepartureDate(today);
        a.setArrivalDate(today.plusDays(5));
        TripDelayRepository repository = org.mockito.Mockito.mock(TripDelayRepository.class);
        LocalDateTime planned = ArrivalRules.renewedPickupCodeExpiry(a);

        assertThat(ArrivalRules.renewedPickupCodeExpiry(a, null)).isEqualTo(planned);
        org.mockito.Mockito.when(repository.findFirstByAnnouncementIdOrderByCreatedAtDesc(a.getId()))
                .thenReturn(java.util.Optional.empty());
        assertThat(ArrivalRules.renewedPickupCodeExpiry(a, repository)).isEqualTo(planned);
        // Un retard dont la fin précède l'arrivée de l'annonce ne la raccourcit jamais.
        org.mockito.Mockito.when(repository.findFirstByAnnouncementIdOrderByCreatedAtDesc(a.getId()))
                .thenReturn(java.util.Optional.of(delayEndingOn(today.plusDays(2))));
        assertThat(ArrivalRules.renewedPickupCodeExpiry(a, repository)).isEqualTo(planned);
        // Bateau : la fin vit sur l'annonce, le dépôt n'est pas lu.
        a.setTransportMode(TransportMode.BOAT);
        a.setArrivalDateMax(today.plusDays(9));
        org.mockito.Mockito.clearInvocations(repository);
        assertThat(ArrivalRules.renewedPickupCodeExpiry(a, repository))
                .isEqualTo(ArrivalRules.renewedPickupCodeExpiry(a));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("décalage d'arrivée d'un modèle ou d'une série : borne du mode, 422 arrival-too-far avec le nombre de jours")
    void assertOffsetWithinMode_perMode() {
        assertThatCode(() -> ArrivalRules.assertOffsetWithinMode(null, TransportMode.PLANE)).doesNotThrowAnyException();
        assertThatCode(() -> ArrivalRules.assertOffsetWithinMode(3, TransportMode.PLANE)).doesNotThrowAnyException();
        assertThatCode(() -> ArrivalRules.assertOffsetWithinMode(14, TransportMode.CAR)).doesNotThrowAnyException();
        assertThatCode(() -> ArrivalRules.assertOffsetWithinMode(120, TransportMode.BOAT)).doesNotThrowAnyException();

        assertThatThrownBy(() -> ArrivalRules.assertOffsetWithinMode(4, TransportMode.PLANE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("arrival-too-far");
                    assertThat(ex.getMessage()).contains("3");
                });
        assertThatThrownBy(() -> ArrivalRules.assertOffsetWithinMode(15, TransportMode.BUS))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getMessage()).contains("14"));
        assertThatThrownBy(() -> ArrivalRules.assertOffsetWithinMode(121, TransportMode.BOAT))
                .isInstanceOf(YadonyBusinessException.class);
    }

    // ─── Bateau (lot 3) : fourchette d'arrivée estimée ──────────────────────────

    private static AnnouncementEntity boatAnn(LocalDate min, LocalDate max) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTransportMode(TransportMode.BOAT);
        a.setDepartureDate(DEP);
        a.setArrivalDate(min);
        a.setArrivalDateMax(max);
        return a;
    }

    @Test
    @DisplayName("code de retrait d'un bateau : calé sur la FIN de la fourchette, même règle sans heure")
    void pickupCodeExpiry_usesArrivalMax() {
        LocalDate min = DEP.plusDays(30);
        LocalDate max = DEP.plusDays(40);

        assertThat(ArrivalRules.pickupCodeExpiry(boatAnn(min, max))).isEqualTo(max.atStartOfDay().plusDays(3));
    }

    @Test
    @DisplayName("code de retrait d'un bateau avec heure d'arrivée : fin de fourchette + heure + 24 h")
    void pickupCodeExpiry_usesArrivalMaxWithTime() {
        AnnouncementEntity a = boatAnn(DEP.plusDays(30), DEP.plusDays(40));
        a.setArrivalTime(LocalTime.of(8, 0));

        assertThat(ArrivalRules.pickupCodeExpiry(a)).isEqualTo(DEP.plusDays(41).atTime(8, 0));
    }

    @Test
    @DisplayName("sans fourchette : expiration inchangée (arrivée effective)")
    void pickupCodeExpiry_withoutRange_unchanged() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureDate(DEP);
        a.setArrivalDate(DEP.plusDays(1));

        assertThat(ArrivalRules.pickupCodeExpiry(a)).isEqualTo(DEP.plusDays(1).atStartOfDay().plusDays(3));
        assertThat(ArrivalRules.latestArrivalDate(a)).isEqualTo(DEP.plusDays(1));
    }

    @Test
    @DisplayName("jour d'arrivée effectif d'un bateau : début de fourchette ; au plus tard : la fin")
    void effectiveAndLatestArrival_boat() {
        AnnouncementEntity a = boatAnn(DEP.plusDays(30), DEP.plusDays(40));

        assertThat(ArrivalRules.effectiveArrivalDate(a)).isEqualTo(DEP.plusDays(30));
        assertThat(ArrivalRules.latestArrivalDate(a)).isEqualTo(DEP.plusDays(40));
    }

    @Test
    @DisplayName("fourchette valide, y compris d'un seul jour et jusqu'à départ + 120 j")
    void validateRange_ok() {
        assertThatCode(() -> ArrivalRules.validateRange(DEP, DEP.plusDays(30), DEP.plusDays(40)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ArrivalRules.validateRange(DEP, DEP.plusDays(30), DEP.plusDays(30)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ArrivalRules.validateRange(DEP, DEP.plusDays(100), DEP.plusDays(120)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("début ou fin manquant, fin avant le début : 422 arrival-range-invalid")
    void validateRange_incomplete_rejected() {
        for (LocalDate[] range : new LocalDate[][] {
                {null, DEP.plusDays(40)}, {DEP.plusDays(30), null}, {null, null},
                {DEP.plusDays(30), DEP.plusDays(29)}}) {
            assertThatThrownBy(() -> ArrivalRules.validateRange(DEP, range[0], range[1]))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("arrival-range-invalid");
                    });
        }
    }

    @Test
    @DisplayName("fin au-delà de départ + 120 j : 422 arrival-too-far (120)")
    void validateRange_beyond120_rejected() {
        assertThatThrownBy(() -> ArrivalRules.validateRange(DEP, DEP.plusDays(100), DEP.plusDays(121)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("arrival-too-far");
                    assertThat(ex.getMessage()).contains("120");
                });
    }
}
