package com.yadony.api.requests.repository;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trou comblé à la Task 4 (découvert Task 3) : {@code existsActiveByTravelerAnnouncementId}
 * et {@code findActiveByPackageRequestIdAndTravelerId} listaient les statuts « actifs » en dur
 * en JPQL, sans passer par {@link NegotiationThreadStatus#isActive()} — un thread
 * {@code AWAITING_COMMISSION} n'y apparaissait pas. Conséquence avant correction : un voyageur
 * pouvait dépublier le trajet lié à un accord cash en attente de règlement, et un second
 * voyageur pouvait poser une offre en doublon sur la même demande pendant qu'un accord cash
 * était encore en jeu. Ces tests exécutent les requêtes en SQL réel (H2, profil test) pour le
 * prouver — un test sur la seule liste Java ne suffit pas, la liste vit dans la chaîne JPQL.
 */
@DataJpaTest
@ActiveProfiles("test")
class NegotiationThreadRepositoryAwaitingCommissionTest {

    @Autowired
    private NegotiationThreadRepository threadRepo;

    @Autowired
    private PackageRequestRepository requestRepo;

    private PackageRequestEntity persistRequest(UUID senderId) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(senderId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.NEGOTIATING);
        return requestRepo.saveAndFlush(e);
    }

    /** Profondeur du balayage de rattrapage, alignée sur le défaut de 7 jours. */
    private static final LocalDateTime SWEEP_SINCE =
        LocalDateTime.now(ZoneOffset.UTC).minusDays(7);

    private NegotiationThreadEntity persistThread(UUID packageRequestId, UUID travelerId,
                                                   UUID travelerAnnouncementId,
                                                   NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(packageRequestId);
        t.setTravelerId(travelerId);
        t.setTravelerAnnouncementId(travelerAnnouncementId);
        t.setTravelerTravelDate(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("10"));
        t.setStatus(status);
        t.setCurrency("EUR");
        t.setCurrentPriceEur(new BigDecimal("35"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        return threadRepo.saveAndFlush(t);
    }

    @Test
    @DisplayName("findActiveByPackageRequestIdAndTravelerId inclut désormais AWAITING_COMMISSION (détection de doublon d'offre)")
    void findActiveByPackageRequestIdAndTravelerId_includesAwaitingCommission() {
        UUID travelerId = UUID.randomUUID();
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), travelerId, null, NegotiationThreadStatus.AWAITING_COMMISSION);

        var found = threadRepo.findActiveByPackageRequestIdAndTravelerId(request.getId(), travelerId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(thread.getId());
    }

    @Test
    @DisplayName("existsActiveByTravelerAnnouncementId inclut désormais AWAITING_COMMISSION (blocage de dépublication)")
    void existsActiveByTravelerAnnouncementId_includesAwaitingCommission() {
        UUID travelerAnnouncementId = UUID.randomUUID();
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        persistThread(request.getId(), UUID.randomUUID(), travelerAnnouncementId,
            NegotiationThreadStatus.AWAITING_COMMISSION);

        boolean active = threadRepo.existsActiveByTravelerAnnouncementId(travelerAnnouncementId);

        assertThat(active).isTrue();
    }

    @Test
    @DisplayName("existsActiveByTravelerAnnouncementId reste false pour un thread AUTO_REJECTED (non-régression)")
    void existsActiveByTravelerAnnouncementId_excludesTerminalStatus() {
        UUID travelerAnnouncementId = UUID.randomUUID();
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        persistThread(request.getId(), UUID.randomUUID(), travelerAnnouncementId,
            NegotiationThreadStatus.AUTO_REJECTED);

        boolean active = threadRepo.existsActiveByTravelerAnnouncementId(travelerAnnouncementId);

        assertThat(active).isFalse();
    }

    @Test
    @DisplayName("findExpiredAwaitingCommission trouve un thread AWAITING_COMMISSION dont lastActivityAt dépasse le cutoff")
    void findExpiredAwaitingCommission_pastCutoff_isFound() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.AWAITING_COMMISSION);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(3));
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findExpiredAwaitingCommission(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));

        assertThat(found).extracting(NegotiationThreadEntity::getId).containsExactly(thread.getId());
    }

    @Test
    @DisplayName("findExpiredAwaitingCommission ignore un thread encore dans la fenêtre (lastActivityAt récent)")
    void findExpiredAwaitingCommission_withinWindow_isExcluded() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.AWAITING_COMMISSION);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findExpiredAwaitingCommission(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findExpiredAwaitingCommission ignore les statuts autres que AWAITING_COMMISSION (même hors délai)")
    void findExpiredAwaitingCommission_excludesOtherStatuses() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.OPEN);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(3));
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findExpiredAwaitingCommission(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions trouve un thread AUTO_REJECTED avec commissionPaymentIntentId non remboursé")
    void findUnrefundedChargedCommissions_autoRejectedWithChargedPi_isFound() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.AUTO_REJECTED);
        thread.setCommissionPaymentIntentId("pi_orphan");
        thread.setCommissionStatus("REQUIRES_3DS");
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).extracting(NegotiationThreadEntity::getId).containsExactly(thread.getId());
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions trouve un thread EXPIRED avec commissionPaymentIntentId non remboursé")
    void findUnrefundedChargedCommissions_expiredWithChargedPi_isFound() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.EXPIRED);
        thread.setCommissionPaymentIntentId("pi_orphan_2");
        thread.setCommissionStatus("CHARGED");
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).extracting(NegotiationThreadEntity::getId).containsExactly(thread.getId());
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions exclut un thread déjà REFUNDED (jamais rejoué)")
    void findUnrefundedChargedCommissions_alreadyRefunded_isExcluded() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.AUTO_REJECTED);
        thread.setCommissionPaymentIntentId("pi_already_refunded");
        thread.setCommissionStatus("REFUNDED");
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions exclut un thread sans commissionPaymentIntentId (rien n'a jamais été débité)")
    void findUnrefundedChargedCommissions_noPaymentIntent_isExcluded() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        persistThread(request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.AUTO_REJECTED);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions exclut un thread ACCEPTED (statut non terminal-perdant), même avec un PaymentIntent")
    void findUnrefundedChargedCommissions_excludesNonTerminalStatus() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.ACCEPTED);
        thread.setCommissionPaymentIntentId("pi_sealed");
        thread.setCommissionStatus("CHARGED");
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions inclut un thread CANCELLED : renoncer après un débit réel ne doit pas coûter la commission")
    void findUnrefundedChargedCommissions_cancelledAfterCharge_isFound() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.CANCELLED);
        thread.setCommissionPaymentIntentId("pi_declined_after_charge");
        thread.setCommissionStatus("REQUIRES_3DS");
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).extracting(NegotiationThreadEntity::getId).containsExactly(thread.getId());
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions exclut REFUND_FAILED : Stripe a déjà refusé, rejouer n'a jamais remboursé personne")
    void findUnrefundedChargedCommissions_refundFailed_isExcluded() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.AUTO_REJECTED);
        thread.setCommissionPaymentIntentId("pi_refund_refused");
        thread.setCommissionStatus("REFUND_FAILED");
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findUnrefundedChargedCommissions exclut les fils trop anciens : sans borne, une 3DS abandonnée serait relue chez Stripe à vie")
    void findUnrefundedChargedCommissions_beyondSweepWindow_isExcluded() {
        PackageRequestEntity request = persistRequest(UUID.randomUUID());
        NegotiationThreadEntity thread = persistThread(
            request.getId(), UUID.randomUUID(), null, NegotiationThreadStatus.AUTO_REJECTED);
        thread.setCommissionPaymentIntentId("pi_long_dead");
        thread.setCommissionStatus("REQUIRES_3DS");
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        threadRepo.saveAndFlush(thread);

        var found = threadRepo.findUnrefundedChargedCommissions(SWEEP_SINCE);

        assertThat(found).isEmpty();
    }
}
