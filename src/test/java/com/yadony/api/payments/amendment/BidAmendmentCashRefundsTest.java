package com.yadony.api.payments.amendment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.IdempotencyException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.amendment.BidAmendmentCashLedger.CardRefundClaim;
import com.yadony.api.payments.amendment.BidAmendmentCashLedger.ChargeOutcome;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Commission d'une modification espèces rendue au voyageur quand la modification n'aboutit pas. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidAmendmentCashRefunds : commission rendue")
class BidAmendmentCashRefundsTest {

    @Mock BidAmendmentCashLedger ledger;
    @Mock StripeSupplementGateway gateway;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentCashRefunds refunds;
    private BidAmendmentEntity a;

    @BeforeEach
    void setUp() {
        refunds = new BidAmendmentCashRefunds(ledger, gateway, alerts);
        a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(UUID.randomUUID());
        a.setTravelerId(UUID.randomUUID());
        a.setRail(BidSupplementRail.CASH);
        a.setStatus(BidAmendmentStatus.DECLINED);
    }

    private void found() {
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
    }

    private void chargedByCard() {
        found();
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        a.setCommissionPaymentIntentId("pi_c");
        when(ledger.cardRefundClaim(a.getId())).thenReturn(Optional.of(
                new CardRefundClaim(a.getId(), a.getBidId(), a.getTravelerId(), "pi_c")));
    }

    @Test
    @DisplayName("solde : recrédité, aucun appel Stripe")
    void wallet_recredited() {
        found();
        a.setCommissionChargedVia(CommissionChargedVia.WALLET);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        when(ledger.refundWallet(a.getId())).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("carte : Refund Stripe avec la clé dédiée et la métadonnée bid_amendment_id")
    void card_refundWithDedicatedKey() throws Exception {
        chargedByCard();
        when(gateway.findRefund("pi_c", "bid_amendment_id", a.getId().toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq("pi_c"), anyMap(), eq("amendment_commission_refund_pi_c")))
                .thenReturn("re_1");
        when(ledger.cardRefunded(a.getId(), "re_1", "refund", true)).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
    }

    @Test
    @DisplayName("carte : Refund déjà créé, retrouvé sans en créer un second")
    void card_existingRefundFoundNotRecreated() throws Exception {
        chargedByCard();
        when(gateway.findRefund("pi_c", "bid_amendment_id", a.getId().toString())).thenReturn(Optional.of("re_old"));
        when(ledger.cardRefunded(a.getId(), "re_old", "refund", true)).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
        verify(gateway, never()).createRefund(any(), any(), any());
    }

    @Test
    @DisplayName("carte : échec Stripe, reste prélevée, alerte et trace une seule fois")
    void card_stripeFailure_alertsOnce() throws Exception {
        chargedByCard();
        when(gateway.findRefund(any(), anyString(), anyString())).thenThrow(new ApiConnectionException("timeout"));
        when(alerts.raiseOnce(eq("AMEND_COMM_REFUND_FAIL_" + a.getId()), anyString(), anyMap()))
                .thenReturn(true, false);

        assertThat(refunds.settle(a.getId())).isFalse();
        assertThat(refunds.settle(a.getId())).isFalse();

        verify(ledger, never()).cardRefunded(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(ledger, times(1)).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_COMMISSION_REFUND_ERROR"), anyMap());
    }

    @Test
    @DisplayName("carte : deux passages simultanés (clé d'idempotence en vol), ni alerte ni trace")
    void card_idempotencyKeyInUse_noAlert() throws Exception {
        chargedByCard();
        when(gateway.findRefund("pi_c", "bid_amendment_id", a.getId().toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq("pi_c"), anyMap(), anyString())).thenThrow(new IdempotencyException(
                "Keys for idempotent requests can only be used with the same parameters", "req",
                "idempotency_key_in_use", 409));

        assertThat(refunds.settle(a.getId())).isFalse();

        verifyNoInteractions(alerts);
        verify(ledger, never()).auditOnly(any(), any(), any());
    }

    @Test
    @DisplayName("carte : échec mais déjà rendue par un autre passage, rien à signaler")
    void card_failureButAlreadyRefundedElsewhere_nothingToReport() throws Exception {
        chargedByCard();
        when(gateway.findRefund(any(), anyString(), anyString())).thenThrow(new ApiConnectionException("timeout"));
        BidAmendmentEntity refunded = new BidAmendmentEntity();
        refunded.setCommissionStatus(CommissionStatus.REFUNDED);
        when(ledger.find(a.getId())).thenReturn(Optional.of(a), Optional.of(refunded));

        assertThat(refunds.settle(a.getId())).isFalse();

        verifyNoInteractions(alerts);
        verify(ledger, never()).auditOnly(any(), any(), any());
    }

    @Test
    @DisplayName("carte : plus de demande de remboursement (rejeu), rien")
    void card_noClaim_nothing() {
        found();
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        when(ledger.cardRefundClaim(a.getId())).thenReturn(Optional.empty());

        assertThat(refunds.settle(a.getId())).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("3DS abandonnée : PaymentIntent annulée, commission FAILED")
    void abandon_cancelsPaymentIntent() throws Exception {
        found();
        a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        a.setCommissionPaymentIntentId("pi_c");
        when(ledger.commissionFailed(a.getId(), "pi_c", null, "abandoned")).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
        verify(gateway).cancelPaymentIntent("pi_c");
    }

    @Test
    @DisplayName("PaymentIntent posée sans statut (annulation avant la 3DS) : annulée aussi")
    void abandon_nullStatusWithPaymentIntent() throws Exception {
        found();
        a.setCommissionPaymentIntentId("pi_c");
        when(ledger.commissionFailed(a.getId(), "pi_c", null, "abandoned")).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
    }

    @Test
    @DisplayName("annulation refusée parce que la 3DS a abouti : commission enregistrée puis à rendre")
    void abandon_cancelRefusedBecauseSucceeded_registersLateCommission() throws Exception {
        found();
        a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        a.setCommissionPaymentIntentId("pi_c");
        when(gateway.cancelPaymentIntent("pi_c")).thenThrow(new ApiConnectionException("already succeeded"));
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(new PaymentIntentView("pi_c", "succeeded", 360L,
                "eur", null, null, List.of("card")));
        when(ledger.commissionCharged(a.getId(), "pi_c", null, "cancel-refused")).thenReturn(ChargeOutcome.LATE_REFUND);

        assertThat(refunds.settle(a.getId())).isTrue();
    }

    @Test
    @DisplayName("annulation refusée, PaymentIntent déjà annulée : commission FAILED")
    void abandon_cancelRefusedAlreadyCanceled_marksFailed() throws Exception {
        found();
        a.setCommissionPaymentIntentId("pi_c");
        when(gateway.cancelPaymentIntent("pi_c")).thenThrow(new ApiConnectionException("canceled"));
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(new PaymentIntentView("pi_c", "canceled", 360L,
                "eur", null, null, List.of("card")));
        when(ledger.commissionFailed(a.getId(), "pi_c", null, "canceled")).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
    }

    @Test
    @DisplayName("annulation refusée, PaymentIntent encore en cours ou illisible : le filet réessaiera")
    void abandon_stillPendingOrUnreadable_retryLater() throws Exception {
        found();
        a.setCommissionPaymentIntentId("pi_c");
        when(gateway.cancelPaymentIntent("pi_c")).thenThrow(new ApiConnectionException("refused"));
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(new PaymentIntentView("pi_c", "processing", 360L,
                "eur", null, null, List.of("card"))).thenThrow(new ApiConnectionException("timeout"));

        assertThat(refunds.settle(a.getId())).isFalse();
        assertThat(refunds.settle(a.getId())).isFalse();
        verify(ledger, never()).commissionFailed(any(), any(), any(), any());
    }

    @Test
    @DisplayName("acceptée sans rien à rendre (commission nulle) : passée à REFUNDED")
    void acceptedWithoutCommission_closed() {
        found();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        when(ledger.closeAccepted(a.getId())).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
    }

    @Test
    @DisplayName("acceptée, commission déjà rendue chez Stripe avant nous : passée à REFUNDED")
    void acceptedWhereCommissionAlreadyRefunded_closed() {
        found();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        a.setCommissionStatus(CommissionStatus.REFUNDED);
        when(ledger.closeAccepted(a.getId())).thenReturn(true);

        assertThat(refunds.settle(a.getId())).isTrue();
    }

    @Test
    @DisplayName("modification ouverte, autre rail ou close sans commission : rien")
    void notSettleable_nothing() {
        found();
        a.setStatus(BidAmendmentStatus.PROPOSED);
        assertThat(refunds.settle(a.getId())).isFalse();

        a.setStatus(BidAmendmentStatus.COMMISSION_PENDING);
        assertThat(refunds.settle(a.getId())).isFalse();

        a.setStatus(BidAmendmentStatus.DECLINED);
        assertThat(refunds.settle(a.getId())).as("refusée avant toute commission").isFalse();

        a.setRail(BidSupplementRail.STRIPE);
        assertThat(refunds.settle(a.getId())).isFalse();
        verifyNoInteractions(gateway, alerts);
    }

    @Test
    @DisplayName("modification inconnue : rien")
    void unknownAmendment_nothing() {
        assertThat(refunds.settle(UUID.randomUUID())).isFalse();
        verifyNoInteractions(gateway, alerts);
    }

    // ── PaymentIntent hors essai ─────────────────────────────────────────────

    @Test
    @DisplayName("PaymentIntent hors essai : alerte une fois, Refund de CETTE PaymentIntent, clé par PaymentIntent, audit")
    void stale_refundedByPaymentIntentKey_withAlert() throws Exception {
        when(gateway.findRefund("pi_old", "bid_amendment_id", a.getId().toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq("pi_old"), anyMap(), eq("amendment_commission_refund_pi_old")))
                .thenReturn("re_stale");

        assertThat(refunds.refundStale(a.getId(), a.getBidId(), "pi_old")).isTrue();

        verify(alerts).raiseOnce(eq("AMEND_STALE_COMM_pi_old"), anyString(), anyMap());
        verify(ledger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_STALE_COMMISSION_REFUNDED"), anyMap());
    }

    @Test
    @DisplayName("PaymentIntent hors essai : Refund déjà créé retrouvé, jamais un second")
    void stale_existingRefundFound() throws Exception {
        when(gateway.findRefund("pi_old", "bid_amendment_id", a.getId().toString()))
                .thenReturn(Optional.of("re_old"));

        assertThat(refunds.refundStale(a.getId(), a.getBidId(), "pi_old")).isTrue();

        verify(gateway, never()).createRefund(any(), any(), any());
    }

    @Test
    @DisplayName("PaymentIntent hors essai : échec du Refund, seconde alerte (rien ne le rejoue) ; idempotence en vol sans alerte")
    void stale_refundFailure_secondAlert_idempotencyInFlightSilent() throws Exception {
        when(gateway.findRefund(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(gateway.createRefund(any(), anyMap(), anyString())).thenThrow(new ApiConnectionException("timeout"))
                .thenThrow(new IdempotencyException("in use", "req", "idempotency_key_in_use", 409));

        assertThat(refunds.refundStale(a.getId(), a.getBidId(), "pi_old")).isFalse();
        verify(alerts).raiseOnce(eq("AMEND_STALE_REFUND_FAIL_pi_old"), anyString(), anyMap());

        assertThat(refunds.refundStale(a.getId(), a.getBidId(), "pi_old")).isFalse();
        verify(alerts, times(1)).raiseOnce(eq("AMEND_STALE_REFUND_FAIL_pi_old"), anyString(), anyMap());
        verify(ledger, never()).auditOnly(any(), any(), any());
    }
}
