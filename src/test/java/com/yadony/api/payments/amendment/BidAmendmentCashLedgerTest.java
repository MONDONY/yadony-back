package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.amendment.BidAmendmentCashLedger.ChargeOutcome;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentStaleCommissionEvent;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Gardes du registre de la commission espèces : verrous, essais, rejeux, états inattendus. Le nominal est dans l'IT. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidAmendmentCashLedger : gardes, essais et rejeux")
class BidAmendmentCashLedgerTest {

    @Mock BidAmendmentRepository amendments;
    @Mock BidAmendmentService service;
    @Mock BidRepository bids;
    @Mock AnnouncementRepository announcements;
    @Mock WalletService walletService;
    @Mock WalletTransactionRepository walletTransactions;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentCashLedger ledger;
    private BidAmendmentEntity a;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        ledger = new BidAmendmentCashLedger(amendments, service, bids, announcements, walletService,
                walletTransactions, auditService, events, new BidAmendmentWindow(new BidAmendmentProperties()),
                alerts);
        a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(UUID.randomUUID());
        a.setAnnouncementId(UUID.randomUUID());
        a.setTravelerId(UUID.randomUUID());
        a.setRail(BidSupplementRail.CASH);
        a.setStatus(BidAmendmentStatus.COMMISSION_PENDING);
        a.setCurrency("EUR");
        a.setCommissionAmount(new BigDecimal("3.60"));
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        a.setRespondedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        a.setExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(5));
        bid = new BidEntity();
        bid.setAnnouncementId(a.getAnnouncementId());
        bid.setStatus(BidStatus.ACCEPTED);
    }

    /** Colis puis modification, dans cet ordre. */
    private void locked() {
        when(amendments.findBidIdById(a.getId())).thenReturn(Optional.of(a.getBidId()));
        when(bids.lockForUpdate(a.getBidId())).thenReturn(1);
        when(amendments.findByIdAndBidIdForUpdate(a.getId(), a.getBidId())).thenReturn(Optional.of(a));
    }

    /** Colis, trajet puis modification : les transitions qui peuvent clore la modification. */
    private void lockedWithTrip() {
        locked();
        when(bids.findById(a.getBidId())).thenReturn(Optional.of(bid));
    }

    private String attempt() {
        return BidAmendmentCashLedger.attemptOf(a);
    }

    private void startedRetryAfterFailure(String previousPi) {
        a.setCommissionStatus(CommissionStatus.FAILED);
        a.setCommissionPaymentIntentId(previousPi);
    }

    @Test
    @DisplayName("modification introuvable, ou colis disparu : rien, jamais d'exception")
    void unknown_ignored() {
        UUID id = UUID.randomUUID();
        when(amendments.findBidIdById(id)).thenReturn(Optional.empty());

        assertThat(ledger.commissionCharged(id, "pi_c", "1", "webhook")).isEqualTo(ChargeOutcome.IGNORED);
        assertThat(ledger.commissionFailed(id, "pi_c", "1", "x")).isFalse();
        assertThat(ledger.abortRetry(id)).isFalse();
        assertThat(ledger.refundWallet(id)).isFalse();
        assertThat(ledger.cardRefunded(id, "re", "webhook", true)).isFalse();
        assertThat(ledger.commissionRefundFailed(id, "re")).isFalse();
        assertThat(ledger.closeAccepted(id)).isFalse();
        ledger.reportStale(id, "pi_c", "x");

        UUID vanished = UUID.randomUUID();
        when(amendments.findBidIdById(vanished)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThat(ledger.refundWallet(vanished)).isFalse();
        verifyNoInteractions(service, walletService, events);
    }

    // ── Commission réussie ───────────────────────────────────────────────────

    @Test
    @DisplayName("PaymentIntent de l'essai courant, dans les délais : modification acquise, verrous colis puis trajet puis modification")
    void commissionCharged_currentPaymentIntent_acceptsAmendment() {
        lockedWithTrip();
        a.setCommissionPaymentIntentId("pi_c");
        a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", attempt(), "webhook")).isEqualTo(ChargeOutcome.ACCEPTED);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bids, announcements, amendments);
        order.verify(bids).lockForUpdate(a.getBidId());
        order.verify(announcements).findByIdForUpdate(a.getAnnouncementId());
        order.verify(amendments).findByIdAndBidIdForUpdate(a.getId(), a.getBidId());
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        verify(service).markAccepted(a);
    }

    @Test
    @DisplayName("webhook de l'essai courant arrivé avant l'enregistrement de la PaymentIntent : adoptée grâce au numéro d'essai")
    void commissionCharged_webhookBeforeRecording_adoptedByAttemptNumber() {
        lockedWithTrip();

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", attempt(), "webhook")).isEqualTo(ChargeOutcome.ACCEPTED);

        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        assertThat(a.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
        verify(service).markAccepted(a);
    }

    @Test
    @DisplayName("PaymentIntent inconnue (sans numéro d'essai ou d'un autre essai) : jamais adoptée, remboursée")
    void commissionCharged_unknownPaymentIntent_neverAdopted_refunded() {
        lockedWithTrip();

        assertThat(ledger.commissionCharged(a.getId(), "pi_unknown", null, "webhook")).isEqualTo(ChargeOutcome.STALE);
        assertThat(ledger.commissionCharged(a.getId(), "pi_unknown", "123", "webhook")).isEqualTo(ChargeOutcome.STALE);

        assertThat(a.getCommissionPaymentIntentId()).isNull();
        assertThat(a.getCommissionStatus()).isNull();
        verify(service, never()).markAccepted(any());
        verify(events, org.mockito.Mockito.times(2)).publishEvent(
                new BidAmendmentStaleCommissionEvent(a.getId(), a.getBidId(), "pi_unknown"));
    }

    @Test
    @DisplayName("PaymentIntent d'un essai précédent aboutie après un essai au solde : remboursée, une seule commission retenue")
    void commissionCharged_previousAttemptAfterWalletAcceptance_stale() {
        lockedWithTrip();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        a.setCommissionChargedVia(CommissionChargedVia.WALLET);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        a.setCommissionPaymentIntentId(null);

        assertThat(ledger.commissionCharged(a.getId(), "pi_1", "111", "webhook")).isEqualTo(ChargeOutcome.STALE);

        assertThat(a.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);
        assertThat(a.getCommissionPaymentIntentId()).isNull();
        verify(service, never()).markAccepted(any());
        verify(events).publishEvent(new BidAmendmentStaleCommissionEvent(a.getId(), a.getBidId(), "pi_1"));
    }

    @Test
    @DisplayName("PaymentIntent de l'essai précédent aboutie pendant un essai par carte : remboursée, l'essai continue")
    void commissionCharged_previousAttemptDuringSecondCardAttempt_stale() {
        lockedWithTrip();
        a.setCommissionPaymentIntentId("pi_2");
        a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);

        assertThat(ledger.commissionCharged(a.getId(), "pi_1", "111", "webhook")).isEqualTo(ChargeOutcome.STALE);

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.COMMISSION_PENDING);
        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_2");
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("nouvel essai en cours : la PaymentIntent de l'essai précédent n'est pas la courante, celle du nouvel essai l'est")
    void commissionCharged_retryInProgress_previousStale_newOneAdopted() {
        lockedWithTrip();
        startedRetryAfterFailure("pi_1");

        assertThat(ledger.commissionCharged(a.getId(), "pi_1", "111", "webhook")).isEqualTo(ChargeOutcome.STALE);
        assertThat(ledger.commissionCharged(a.getId(), "pi_1", attempt(), "webhook")).isEqualTo(ChargeOutcome.STALE);
        verify(service, never()).markAccepted(any());

        assertThat(ledger.commissionCharged(a.getId(), "pi_2", attempt(), "webhook")).isEqualTo(ChargeOutcome.ACCEPTED);

        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_2");
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        verify(service).markAccepted(a);
    }

    @Test
    @DisplayName("3DS d'un essai déclaré échoué qui aboutit : la modification n'est PAS acceptée, la commission est rendue")
    void commissionCharged_failedAttemptSucceedsLate_notAccepted_refunded() {
        lockedWithTrip();
        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setRespondedAt(null);
        startedRetryAfterFailure("pi_1");

        assertThat(ledger.commissionCharged(a.getId(), "pi_1", "111", "webhook")).isEqualTo(ChargeOutcome.STALE);

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
        verify(service, never()).markAccepted(any());
        verify(events).publishEvent(new BidAmendmentStaleCommissionEvent(a.getId(), a.getBidId(), "pi_1"));
    }

    @Test
    @DisplayName("rejeu d'une commission déjà enregistrée : aucun effet")
    void commissionCharged_replay_noEffect() {
        lockedWithTrip();
        a.setCommissionPaymentIntentId("pi_c");
        for (CommissionStatus status : List.of(CommissionStatus.CHARGED, CommissionStatus.REFUNDED,
                CommissionStatus.REFUND_FAILED)) {
            a.setCommissionStatus(status);
            assertThat(ledger.commissionCharged(a.getId(), "pi_c", attempt(), "webhook"))
                    .isEqualTo(ChargeOutcome.ALREADY);
        }
        verifyNoInteractions(service, events);
    }

    @Test
    @DisplayName("3DS terminée après l'échéance de confirmation : commission enregistrée, modification close, commission rendue")
    void commissionCharged_afterConfirmationWindow_closesAndRefunds() {
        lockedWithTrip();
        a.setCommissionPaymentIntentId("pi_c");
        a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        a.setRespondedAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(31));

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", attempt(), "webhook"))
                .isEqualTo(ChargeOutcome.LATE_REFUND);

        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        verify(service).expireUnderBidLock(a);
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("3DS terminée alors que le colis n'est plus accepté : modification close (BID_ENDED), commission rendue")
    void commissionCharged_bidNoLongerAccepted_closesAndRefunds() {
        lockedWithTrip();
        bid.setStatus(BidStatus.IN_TRANSIT);
        a.setCommissionPaymentIntentId("pi_c");

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", attempt(), "webhook"))
                .isEqualTo(ChargeOutcome.LATE_REFUND);

        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        verify(service).cancelOpen(a, BidAmendmentClosedReason.BID_ENDED);
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("modification déjà close : sa propre PaymentIntent est enregistrée puis rendue, une autre est remboursée à part")
    void commissionCharged_closedAmendment() {
        lockedWithTrip();
        a.setStatus(BidAmendmentStatus.CANCELLED);
        a.setCommissionPaymentIntentId("pi_c");
        a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);

        assertThat(ledger.commissionCharged(a.getId(), "pi_other", "1", "webhook")).isEqualTo(ChargeOutcome.STALE);
        verify(events, never()).publishEvent(any(BidAmendmentRefundRequestedEvent.class));

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", null, "webhook")).isEqualTo(ChargeOutcome.LATE_REFUND);

        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.CANCELLED);
        verify(events).publishEvent(any(BidAmendmentRefundRequestedEvent.class));
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("autre rail : ignorée")
    void commissionCharged_otherRail_ignored() {
        lockedWithTrip();
        a.setRail(BidSupplementRail.PAWAPAY);

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", attempt(), "webhook")).isEqualTo(ChargeOutcome.IGNORED);
        verifyNoInteractions(service, events);
    }

    // ── Commission refusée ───────────────────────────────────────────────────

    @Test
    @DisplayName("refus de la commission : PROPOSED, respondedAt remis à zéro, commission FAILED, rien de clos ni rendu")
    void commissionFailed_revertsToProposed_withoutClosing() {
        locked();
        a.setCommissionPaymentIntentId("pi_c");

        assertThat(ledger.commissionFailed(a.getId(), "pi_c", attempt(), "x")).isTrue();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bids, amendments);
        order.verify(bids).lockForUpdate(a.getBidId());
        order.verify(amendments).findByIdAndBidIdForUpdate(a.getId(), a.getBidId());
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
        assertThat(a.getRespondedAt()).isNull();
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        assertThat(a.getClosedReason()).isNull();
        verifyNoInteractions(service, walletService);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("carte refusée à la création (aucune PaymentIntent) : retour en PROPOSED")
    void commissionFailed_withoutPaymentIntent_reverts() {
        locked();

        assertThat(ledger.commissionFailed(a.getId(), null, attempt(), "card-declined")).isTrue();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
        assertThat(a.getCommissionPaymentIntentId()).isNull();
    }

    @Test
    @DisplayName("échec d'une PaymentIntent inconnue alors que l'id enregistré est nul : ignoré, jamais adopté")
    void commissionFailed_unknownPaymentIntent_storedNull_ignored() {
        locked();

        assertThat(ledger.commissionFailed(a.getId(), "pi_unknown", null, "payment_failed")).isFalse();
        assertThat(ledger.commissionFailed(a.getId(), "pi_unknown", "123", "payment_failed")).isFalse();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.COMMISSION_PENDING);
        assertThat(a.getCommissionPaymentIntentId()).isNull();
        assertThat(a.getCommissionStatus()).isNull();
    }

    @Test
    @DisplayName("échec de la PaymentIntent de l'essai courant arrivé avant son enregistrement : adoptée par le numéro d'essai")
    void commissionFailed_currentAttemptBeforeRecording_adopted() {
        locked();

        assertThat(ledger.commissionFailed(a.getId(), "pi_c", attempt(), "payment_failed")).isTrue();

        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
    }

    @Test
    @DisplayName("signal d'une ancienne PaymentIntent pendant un nouvel essai : ignoré, l'essai en cours continue")
    void commissionFailed_previousPaymentIntentDuringRetry_ignored() {
        locked();
        startedRetryAfterFailure("pi_1");

        assertThat(ledger.commissionFailed(a.getId(), "pi_1", "111", "canceled")).isFalse();
        assertThat(ledger.commissionFailed(a.getId(), "pi_other", "111", "canceled")).isFalse();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.COMMISSION_PENDING);
    }

    @Test
    @DisplayName("échec du nouvel essai : retour en PROPOSED avec la PaymentIntent du nouvel essai")
    void commissionFailed_newAttempt_reverts() {
        locked();
        startedRetryAfterFailure("pi_1");

        assertThat(ledger.commissionFailed(a.getId(), "pi_2", attempt(), "payment_failed")).isTrue();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_2");
    }

    @Test
    @DisplayName("refus sur une modification déjà close : seule la commission est marquée")
    void commissionFailed_onClosedAmendment_marksCommissionOnly() {
        locked();
        a.setStatus(BidAmendmentStatus.CANCELLED);
        a.setCommissionPaymentIntentId("pi_c");

        assertThat(ledger.commissionFailed(a.getId(), "pi_c", null, "abandoned")).isTrue();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.CANCELLED);
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
    }

    @Test
    @DisplayName("commission déjà prélevée, rendue ou refusée : un échec ne l'écrase jamais")
    void commissionFailed_neverOverwritesSettledCommission() {
        locked();
        a.setCommissionPaymentIntentId("pi_c");
        for (CommissionStatus status : List.of(CommissionStatus.CHARGED, CommissionStatus.REFUNDED,
                CommissionStatus.REFUND_FAILED)) {
            a.setCommissionStatus(status);
            assertThat(ledger.commissionFailed(a.getId(), "pi_c", attempt(), "x")).isFalse();
            assertThat(a.getCommissionStatus()).isEqualTo(status);
        }
        a.setCommissionStatus(CommissionStatus.FAILED);
        a.setStatus(BidAmendmentStatus.PROPOSED);
        assertThat(ledger.commissionFailed(a.getId(), "pi_c", attempt(), "x")).as("déjà échouée").isFalse();
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);

        a.setCommissionStatus(null);
        a.setRail(BidSupplementRail.STRIPE);
        assertThat(ledger.commissionFailed(a.getId(), "pi_c", attempt(), "x")).isFalse();
    }

    @Test
    @DisplayName("nouvel essai refusé avant sa PaymentIntent (précédente en cours) : PROPOSED, commission toujours FAILED")
    void abortRetry_revertsToProposed_onlyDuringRetry() {
        locked();
        assertThat(ledger.abortRetry(a.getId())).as("pas un nouvel essai").isFalse();

        startedRetryAfterFailure("pi_1");
        assertThat(ledger.abortRetry(a.getId())).isTrue();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
        assertThat(a.getRespondedAt()).isNull();
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
    }

    @Test
    @DisplayName("PaymentIntent signalée hors essai par le rail : audit et événement de remboursement")
    void reportStale_auditsAndPublishes() {
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));

        ledger.reportStale(a.getId(), "pi_1", "retry-check");

        verify(auditService).log(eq("BID_AMENDMENT"), eq(a.getId()), eq("BID_AMENDMENT_STALE_COMMISSION"), any(),
                anyMap());
        verify(events).publishEvent(new BidAmendmentStaleCommissionEvent(a.getId(), a.getBidId(), "pi_1"));
    }

    // ── 3DS ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3DS : la PaymentIntent du premier essai est posée, REQUIRES_3DS ; un rejeu ne la remplace pas")
    void requiresAction_recordsPaymentIntent_firstAttempt() {
        locked();

        assertThat(ledger.commissionRequiresAction(a.getId(), "pi_c", attempt()).getCommissionStatus())
                .isEqualTo(CommissionStatus.REQUIRES_3DS);
        ledger.commissionRequiresAction(a.getId(), "pi_late", attempt());
        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_c");

        a.setCommissionStatus(null);
        a.setStatus(BidAmendmentStatus.CANCELLED);
        assertThat(ledger.commissionRequiresAction(a.getId(), "pi_c", attempt()).getCommissionStatus()).isNull();
    }

    @Test
    @DisplayName("3DS d'un nouvel essai : sa PaymentIntent remplace celle de l'essai précédent")
    void requiresAction_newAttempt_replacesPreviousPaymentIntent() {
        locked();
        startedRetryAfterFailure("pi_1");

        BidAmendmentEntity updated = ledger.commissionRequiresAction(a.getId(), "pi_2", attempt());

        assertThat(updated.getCommissionPaymentIntentId()).isEqualTo("pi_2");
        assertThat(updated.getCommissionStatus()).isEqualTo(CommissionStatus.REQUIRES_3DS);
    }

    @Test
    @DisplayName("3DS d'un nouvel essai déjà clos pendant le débit : la PaymentIntent n'est pas enregistrée")
    void requiresAction_closedDuringRetry_notRecorded() {
        locked();
        startedRetryAfterFailure("pi_1");
        a.setStatus(BidAmendmentStatus.CANCELLED);

        BidAmendmentEntity updated = ledger.commissionRequiresAction(a.getId(), "pi_2", attempt());

        assertThat(updated.getCommissionPaymentIntentId()).isEqualTo("pi_1");
    }

    @Test
    @DisplayName("recrédit du solde : refusé hors statut remboursable, hors portefeuille ou déjà rendu")
    void refundWallet_guards() {
        locked();
        a.setCommissionChargedVia(CommissionChargedVia.WALLET);
        a.setCommissionStatus(CommissionStatus.CHARGED);

        a.setStatus(BidAmendmentStatus.PROPOSED);
        assertThat(ledger.refundWallet(a.getId())).as("encore ouverte").isFalse();
        a.setStatus(BidAmendmentStatus.CANCELLED);
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        assertThat(ledger.refundWallet(a.getId())).as("prélevée par carte").isFalse();
        a.setCommissionChargedVia(CommissionChargedVia.WALLET);
        a.setCommissionStatus(CommissionStatus.REFUNDED);
        assertThat(ledger.refundWallet(a.getId())).as("déjà rendue").isFalse();
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("recrédit sans ligne au grand livre : commission marquée rendue, rien de crédité")
    void refundWallet_noLedgerLines_stillClosesCommission() {
        locked();
        a.setStatus(BidAmendmentStatus.CANCELLED);
        a.setCommissionChargedVia(CommissionChargedVia.WALLET);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        when(walletTransactions.findAllByUserIdAndPaymentRefAndType(a.getTravelerId(), "amendment-" + a.getId(),
                WalletTransactionType.AMENDMENT_COMMISSION_DEDUCTED)).thenReturn(List.of());

        assertThat(ledger.refundWallet(a.getId())).isTrue();

        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
        assertThat(a.getStatus()).as("une modification close garde son statut").isEqualTo(BidAmendmentStatus.CANCELLED);
        verifyNoInteractions(walletService);
        verify(alerts).raiseOnce(eq("AMEND_COMM_NO_LEDGER_" + a.getId()), org.mockito.ArgumentMatchers.anyString(),
                anyMap());
    }

    @Test
    @DisplayName("recrédit : une ligne à zéro ne crédite rien")
    void refundWallet_zeroLineSkipped() {
        locked();
        a.setStatus(BidAmendmentStatus.DECLINED);
        a.setCommissionChargedVia(CommissionChargedVia.WALLET);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        WalletTransactionEntity zero = mock(WalletTransactionEntity.class);
        when(zero.getAmount()).thenReturn(BigDecimal.ZERO);
        when(walletTransactions.findAllByUserIdAndPaymentRefAndType(any(), any(), any())).thenReturn(List.of(zero));

        assertThat(ledger.refundWallet(a.getId())).isTrue();

        verifyNoInteractions(walletService, alerts);
    }

    @Test
    @DisplayName("demande de remboursement carte : seulement une commission carte prélevée d'une modification sans suite")
    void cardRefundClaim_filters() {
        a.setStatus(BidAmendmentStatus.EXPIRED);
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        a.setCommissionPaymentIntentId("pi_c");
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));

        assertThat(ledger.cardRefundClaim(a.getId())).hasValueSatisfying(claim -> {
            assertThat(claim.paymentIntentId()).isEqualTo("pi_c");
            assertThat(claim.bidId()).isEqualTo(a.getBidId());
        });
        a.setStatus(BidAmendmentStatus.PROPOSED);
        assertThat(ledger.cardRefundClaim(a.getId())).isEmpty();
        a.setStatus(BidAmendmentStatus.EXPIRED);
        a.setCommissionPaymentIntentId(null);
        assertThat(ledger.cardRefundClaim(a.getId())).isEmpty();
    }

    @Test
    @DisplayName("remboursement constaté chez Stripe : la commission seule change, la modification vivante garde son statut")
    void cardRefunded_notSettling_keepsAmendmentStatus() {
        locked();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        a.setCommissionStatus(CommissionStatus.CHARGED);

        assertThat(ledger.cardRefunded(a.getId(), "re_1", "webhook", false)).isTrue();

        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.ACCEPTED);
        assertThat(ledger.cardRefunded(a.getId(), "re_1", "webhook", false)).as("rejeu").isFalse();
    }

    @Test
    @DisplayName("Refund échoué après coup : REFUNDED → REFUND_FAILED seulement")
    void commissionRefundFailed_onlyFromRefunded() {
        locked();
        a.setCommissionStatus(CommissionStatus.CHARGED);
        assertThat(ledger.commissionRefundFailed(a.getId(), "re_1")).isFalse();

        a.setCommissionStatus(CommissionStatus.REFUNDED);
        assertThat(ledger.commissionRefundFailed(a.getId(), "re_1")).isTrue();
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.REFUND_FAILED);
    }

    @Test
    @DisplayName("fin d'une acceptée sans commission à rendre : refusée si la commission est encore à rendre")
    void closeAccepted_refusedWhileCommissionStillDue() {
        locked();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        assertThat(ledger.closeAccepted(a.getId())).isFalse();

        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setCommissionStatus(null);
        assertThat(ledger.closeAccepted(a.getId())).isFalse();

        a.setStatus(BidAmendmentStatus.ACCEPTED);
        assertThat(ledger.closeAccepted(a.getId())).isTrue();
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.REFUNDED);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("trace sans transition : une entrée d'audit, jamais de montant")
    void auditOnly_logsEntry() {
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        a.setStatus(BidAmendmentStatus.CANCELLED);

        ledger.auditOnly(a.getId(), "BID_AMENDMENT_CHARGEBACK", java.util.Map.of("event", "x"));
        ledger.auditOnly(UUID.randomUUID(), "BID_AMENDMENT_CHARGEBACK", java.util.Map.of());

        verify(auditService).log(eq("BID_AMENDMENT"), eq(a.getId()), eq("BID_AMENDMENT_CHARGEBACK"), any(),
                anyMap());
    }
}
