package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.amendment.BidAmendmentCashLedger.ChargeOutcome;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
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

/** Gardes du registre de la commission espèces : verrous, rejeux, états inattendus. Le parcours nominal est dans l'IT. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidAmendmentCashLedger : gardes et rejeux")
class BidAmendmentCashLedgerTest {

    @Mock BidAmendmentRepository amendments;
    @Mock BidAmendmentService service;
    @Mock BidRepository bids;
    @Mock WalletService walletService;
    @Mock WalletTransactionRepository walletTransactions;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentCashLedger ledger;
    private BidAmendmentEntity a;

    @BeforeEach
    void setUp() {
        ledger = new BidAmendmentCashLedger(amendments, service, bids, walletService,
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
    }

    /** Colis puis modification, dans cet ordre. */
    private void locked() {
        when(amendments.findBidIdById(a.getId())).thenReturn(Optional.of(a.getBidId()));
        when(bids.lockForUpdate(a.getBidId())).thenReturn(1);
        when(amendments.findByIdAndBidIdForUpdate(a.getId(), a.getBidId())).thenReturn(Optional.of(a));
    }

    @Test
    @DisplayName("modification introuvable, ou colis disparu : rien, jamais d'exception")
    void unknown_ignored() {
        UUID id = UUID.randomUUID();
        when(amendments.findBidIdById(id)).thenReturn(Optional.empty());

        assertThat(ledger.commissionCharged(id, "pi_c", "webhook")).isEqualTo(ChargeOutcome.IGNORED);
        assertThat(ledger.commissionFailed(id, "pi_c", "x")).isFalse();
        assertThat(ledger.refundWallet(id)).isFalse();
        assertThat(ledger.cardRefunded(id, "re", "webhook", true)).isFalse();
        assertThat(ledger.commissionRefundFailed(id, "re")).isFalse();
        assertThat(ledger.closeAccepted(id)).isFalse();

        UUID vanished = UUID.randomUUID();
        when(amendments.findBidIdById(vanished)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThat(ledger.refundWallet(vanished)).isFalse();
        verifyNoInteractions(service, walletService);
    }

    @Test
    @DisplayName("refus de la commission : PROPOSED, respondedAt remis à zéro, commission FAILED, rien de clos ni rendu")
    void commissionFailed_revertsToProposed_withoutClosing() {
        locked();
        a.setRespondedAt(java.time.LocalDateTime.now());

        assertThat(ledger.commissionFailed(a.getId(), "pi_c", "x")).isTrue();

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
    @DisplayName("refus sur une modification déjà close : seule la commission est marquée")
    void commissionFailed_onClosedAmendment_marksCommissionOnly() {
        locked();
        a.setStatus(BidAmendmentStatus.CANCELLED);

        assertThat(ledger.commissionFailed(a.getId(), null, "abandoned")).isTrue();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.CANCELLED);
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
    }

    @Test
    @DisplayName("signal d'une ancienne PaymentIntent (essai précédent) : ignoré")
    void commissionFailed_stalePaymentIntent_ignored() {
        locked();
        a.setCommissionPaymentIntentId("pi_new");

        assertThat(ledger.commissionFailed(a.getId(), "pi_old", "canceled")).isFalse();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.COMMISSION_PENDING);
        assertThat(a.getCommissionStatus()).isNull();
    }

    @Test
    @DisplayName("commission déjà prélevée, rendue ou refusée : un échec ne l'écrase jamais")
    void commissionFailed_neverOverwritesSettledCommission() {
        locked();
        for (CommissionStatus status : List.of(CommissionStatus.CHARGED, CommissionStatus.REFUNDED,
                CommissionStatus.REFUND_FAILED, CommissionStatus.FAILED)) {
            a.setCommissionStatus(status);
            assertThat(ledger.commissionFailed(a.getId(), "pi_c", "x")).isFalse();
            assertThat(a.getCommissionStatus()).isEqualTo(status);
        }
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.COMMISSION_PENDING);

        a.setCommissionStatus(null);
        a.setRail(BidSupplementRail.STRIPE);
        assertThat(ledger.commissionFailed(a.getId(), "pi_c", "x")).isFalse();
    }

    @Test
    @DisplayName("3DS d'un essai déclaré échoué qui aboutit finalement : la commission est payée, modification acquise")
    void commissionCharged_onProposedAfterFailure_acceptsAmendment() {
        locked();
        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setCommissionStatus(CommissionStatus.FAILED);
        a.setCommissionPaymentIntentId("pi_c");

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", "webhook")).isEqualTo(ChargeOutcome.ACCEPTED);

        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        verify(service).markAccepted(a);
    }

    @Test
    @DisplayName("commission réussie : autre rail ou autre PaymentIntent ignorées, rejeu sans effet")
    void commissionCharged_guardsAndReplay() {
        locked();
        a.setCommissionPaymentIntentId("pi_mine");

        assertThat(ledger.commissionCharged(a.getId(), "pi_other", "webhook")).isEqualTo(ChargeOutcome.IGNORED);

        for (CommissionStatus status : List.of(CommissionStatus.CHARGED, CommissionStatus.REFUNDED,
                CommissionStatus.REFUND_FAILED)) {
            a.setCommissionStatus(status);
            assertThat(ledger.commissionCharged(a.getId(), "pi_mine", "webhook")).isEqualTo(ChargeOutcome.ALREADY);
        }

        a.setCommissionStatus(null);
        a.setRail(BidSupplementRail.PAWAPAY);
        assertThat(ledger.commissionCharged(a.getId(), "pi_mine", "webhook")).isEqualTo(ChargeOutcome.IGNORED);
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("webhook arrivé avant la pose de la PaymentIntent : elle est enregistrée puis la modification acquise")
    void commissionCharged_webhookBeforePaymentIntentRecorded() {
        locked();

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", "webhook")).isEqualTo(ChargeOutcome.ACCEPTED);

        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        assertThat(a.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
        verify(service).markAccepted(a);
    }

    @Test
    @DisplayName("commission arrivée sur une modification close : événement de remboursement, jamais acquise")
    void commissionCharged_onClosedAmendment_requestsRefund() {
        locked();
        a.setStatus(BidAmendmentStatus.CANCELLED);

        assertThat(ledger.commissionCharged(a.getId(), "pi_c", "webhook")).isEqualTo(ChargeOutcome.LATE_REFUND);

        verify(events).publishEvent(any(BidAmendmentRefundRequestedEvent.class));
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("3DS : la PaymentIntent est posée une fois, le statut REQUIRES_3DS seulement en attente")
    void requiresAction_recordsPaymentIntentOnce() {
        locked();

        assertThat(ledger.commissionRequiresAction(a.getId(), "pi_c").getCommissionStatus())
                .isEqualTo(CommissionStatus.REQUIRES_3DS);
        ledger.commissionRequiresAction(a.getId(), "pi_late");
        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_c");

        a.setCommissionStatus(null);
        a.setStatus(BidAmendmentStatus.CANCELLED);
        assertThat(ledger.commissionRequiresAction(a.getId(), "pi_c").getCommissionStatus()).isNull();
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
