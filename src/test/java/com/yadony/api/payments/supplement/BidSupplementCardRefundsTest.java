package com.yadony.api.payments.supplement;

import com.stripe.exception.ApiConnectionException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.supplement.BidSupplementCardLedger.Claim;
import com.yadony.api.payments.supplement.BidSupplementCardLedger.RefundClaim;
import com.yadony.api.payments.supplement.BidSupplementCardLedger.RefundDecision;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementCancelledEvent;
import com.yadony.api.payments.supplement.events.BidSupplementDeclinedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Remboursement du supplément carte, et écouteur qui déclenche annulation, remboursement et versement. */
@ExtendWith(MockitoExtension.class)
class BidSupplementCardRefundsTest {

    @Mock StripeSupplementGateway gateway;
    @Mock BidSupplementCardLedger ledger;
    @Mock AdminAlertEscalator alerts;
    @Mock BidSupplementCardRail rail;
    @Mock BidSupplementCardPayouts payouts;

    private final UUID id = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    private Claim claim(String piId) {
        return new Claim(id, bidId, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("20.00"),
                new BigDecimal("21.10"), "EUR", piId, "ch_1");
    }

    private BidSupplementCardRefunds refunds() {
        return new BidSupplementCardRefunds(gateway, ledger, alerts);
    }

    @Test
    void remboursement_refundIntegralAvecCleDediee() throws Exception {
        when(ledger.beginRefund(id)).thenReturn(new RefundClaim(RefundDecision.PROCEED, claim("pi_1")));
        when(gateway.findRefund("pi_1", id.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund("pi_1", Map.of("bid_supplement_id", id.toString(),
                "supplement_bid_id", bidId.toString()), "supplement-refund-" + id)).thenReturn("re_1");

        assertThat(refunds().refund(id)).isTrue();
        verify(ledger).completeRefund(id, "re_1", "refund");
    }

    @Test
    void reprise_refundDejaCreeRetrouve_aucunSecondRefund() throws Exception {
        when(ledger.beginRefund(id)).thenReturn(new RefundClaim(RefundDecision.PROCEED, claim("pi_1")));
        when(gateway.findRefund("pi_1", id.toString())).thenReturn(Optional.of("re_0"));

        assertThat(refunds().refund(id)).isTrue();
        verify(gateway, never()).createRefund(any(), any(), any());
        verify(ledger).completeRefund(id, "re_0", "refund");
    }

    @Test
    void echec_resteEnAttenteEtAlerte() throws Exception {
        when(ledger.beginRefund(id)).thenReturn(new RefundClaim(RefundDecision.PROCEED, claim("pi_1")));
        when(gateway.findRefund(anyString(), anyString())).thenThrow(new ApiConnectionException("timeout"));

        assertThat(refunds().refund(id)).isFalse();
        verify(ledger, never()).completeRefund(any(), any(), any());
        verify(ledger).auditOnly(eq(id), eq("BID_SUPPLEMENT_REFUND_FAILED"), anyMap());
        verify(alerts).raiseOnce(eq("SUPP_REFUND_FAILED_" + id), anyString(), anyMap());
    }

    @Test
    void sansPaymentIntent_echecEtAlerte() {
        when(ledger.beginRefund(id)).thenReturn(new RefundClaim(RefundDecision.PROCEED, claim(null)));
        assertThat(refunds().refund(id)).isFalse();
        verify(alerts).raiseOnce(eq("SUPP_REFUND_FAILED_" + id), anyString(), anyMap());
        verifyNoInteractions(gateway);
    }

    @Test
    void dejaVerse_alerteSansRefund_rienARembourser_sansEffet() {
        when(ledger.beginRefund(id)).thenReturn(new RefundClaim(RefundDecision.AFTER_PAYOUT, claim("pi_1")),
                new RefundClaim(RefundDecision.NOTHING, null));

        assertThat(refunds().refund(id)).isFalse();
        verify(alerts).raiseOnce(eq("SUPP_REFUND_PAID_OUT_" + id), anyString(), anyMap());
        assertThat(refunds().refund(id)).isFalse();
        verifyNoInteractions(gateway);
    }

    // ── Écouteur ─────────────────────────────────────────────────────────────

    private BidSupplementCardListener listener() {
        return new BidSupplementCardListener(rail, payouts, refunds());
    }

    @Test
    void ecouteur_demandeCloseEnAttenteDePaiement_annuleLaPaymentIntent() {
        listener().onClosed(new BidSupplementDeclinedEvent(id, bidId, UUID.randomUUID(), UUID.randomUUID(),
                BidSupplementRail.STRIPE, BidSupplementStatus.PAYMENT_PENDING));
        verify(rail).cancelPendingPayment(id);
    }

    @Test
    void ecouteur_demandeCloseSansPaiementOuAutreRail_rien() {
        listener().onClosed(new BidSupplementDeclinedEvent(id, bidId, UUID.randomUUID(), UUID.randomUUID(),
                BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
        listener().onClosed(new BidSupplementCancelledEvent(id, bidId, UUID.randomUUID(), UUID.randomUUID(),
                BidSupplementRail.PAWAPAY, BidSupplementStatus.PAYMENT_PENDING, "BID_REJECTED"));
        verifyNoInteractions(rail);
    }

    @Test
    void ecouteur_echecsAvales_leFiletReprendra() {
        doThrow(new IllegalStateException("x")).when(rail).cancelPendingPayment(id);
        doThrow(new IllegalStateException("x")).when(payouts).payoutForBid(bidId);
        when(ledger.beginRefund(id)).thenThrow(new IllegalStateException("x"));

        listener().onClosed(new BidSupplementDeclinedEvent(id, bidId, UUID.randomUUID(), UUID.randomUUID(),
                BidSupplementRail.STRIPE, BidSupplementStatus.PAYMENT_PENDING));
        listener().onRefundRequested(new BidSupplementRefundRequestedEvent(id, bidId, BidSupplementRail.STRIPE,
                BidSupplementStatus.REFUND_PENDING, "BID_REJECTED"));
        listener().onPaymentReleased(PaymentReleasedEvent.card(bidId, UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.TEN, BigDecimal.ONE, "EUR"));

        verify(payouts).payoutForBid(bidId);
    }

    @Test
    void ecouteur_remboursementDemande_carteSeulement() {
        when(ledger.beginRefund(id)).thenReturn(new RefundClaim(RefundDecision.NOTHING, null));
        listener().onRefundRequested(new BidSupplementRefundRequestedEvent(id, bidId, BidSupplementRail.PAWAPAY,
                BidSupplementStatus.REFUND_PENDING, "BID_REJECTED"));
        verify(ledger, never()).beginRefund(any());

        listener().onRefundRequested(new BidSupplementRefundRequestedEvent(id, bidId, BidSupplementRail.STRIPE,
                BidSupplementStatus.REFUND_PENDING, "BID_REJECTED"));
        verify(ledger).beginRefund(id);
    }

    @Test
    void ecouteur_versementSansColis_ignore() {
        listener().onPaymentReleased(new PaymentReleasedEvent(null, UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.TEN));
        verifyNoInteractions(payouts);
    }
}
