package com.yadony.api.payments.supplement;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.events.BidSupplementDeclinedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Commission d'un supplément espèces rendue au voyageur quand la demande n'aboutit pas. */
@ExtendWith(MockitoExtension.class)
class BidSupplementCashRefundsTest {

    @Mock BidSupplementCashLedger ledger;
    @Mock StripeSupplementGateway gateway;
    @Mock AdminAlertEscalator alerts;

    private BidSupplementCashRefunds refunds;
    private BidSupplementEntity s;

    @BeforeEach
    void setUp() {
        refunds = new BidSupplementCashRefunds(ledger, gateway, alerts);
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(UUID.randomUUID());
        s.setTravelerId(UUID.randomUUID());
        s.setRail(BidSupplementRail.CASH);
        s.setStatus(BidSupplementStatus.DECLINED);
    }

    private void found() {
        when(ledger.find(s.getId())).thenReturn(Optional.of(s));
    }

    @Test
    void solde_recredite() {
        found();
        s.setCommissionChargedVia(CommissionChargedVia.WALLET);
        s.setCommissionStatus(CommissionStatus.CHARGED);
        when(ledger.refundWallet(s.getId())).thenReturn(true);

        assertThat(refunds.settle(s.getId())).isTrue();
        verifyNoInteractions(gateway);
    }

    @Test
    void carte_refundStripeAvecCleDediee() throws Exception {
        chargedByCard();
        when(gateway.findRefund("pi_c", s.getId().toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq("pi_c"), anyMap(), eq("supplement_commission_refund_" + s.getId())))
                .thenReturn("re_1");
        when(ledger.cardRefunded(s.getId(), "re_1", "refund")).thenReturn(true);

        assertThat(refunds.settle(s.getId())).isTrue();
    }

    @Test
    void carte_refundDejaCree_retrouveSansEnCreerUnSecond() throws Exception {
        chargedByCard();
        when(gateway.findRefund("pi_c", s.getId().toString())).thenReturn(Optional.of("re_old"));
        when(ledger.cardRefunded(s.getId(), "re_old", "refund")).thenReturn(true);

        assertThat(refunds.settle(s.getId())).isTrue();
        verify(gateway, never()).createRefund(any(), any(), any());
    }

    @Test
    void carte_echecStripe_resteAPrelevee_alerteEtTraceUneSeuleFois_finDeFile() throws Exception {
        chargedByCard();
        when(gateway.findRefund(any(), any())).thenThrow(new ApiConnectionException("timeout"));
        when(alerts.raiseOnce(eq("SUPP_COMM_REFUND_FAIL_" + s.getId()), anyString(), anyMap())).thenReturn(true, false);

        assertThat(refunds.settle(s.getId())).isFalse();
        assertThat(refunds.settle(s.getId())).isFalse();

        verify(ledger, never()).cardRefunded(any(), any(), any());
        verify(ledger, org.mockito.Mockito.times(1)).auditOnly(eq(s.getId()),
                eq("BID_SUPPLEMENT_COMMISSION_REFUND_ERROR"), anyMap());
        verify(ledger, org.mockito.Mockito.times(2)).touch(s.getId());
    }

    @Test
    void carte_deuxPassagesSimultanes_409IdempotenceChezStripe_niAlerteNiTrace() throws Exception {
        chargedByCard();
        when(gateway.findRefund("pi_c", s.getId().toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq("pi_c"), anyMap(), anyString())).thenThrow(new IdempotencyException(
                "Keys for idempotent requests can only be used with the same parameters", "req", "idempotency_key_in_use",
                409));

        assertThat(refunds.settle(s.getId())).isFalse();

        verify(ledger).touch(s.getId());
        verifyNoInteractions(alerts);
        verify(ledger, never()).auditOnly(any(), any(), any());
    }

    @Test
    void carte_echecMaisDejaRembourseeParUnAutrePassage_rienASignaler() throws Exception {
        chargedByCard();
        when(gateway.findRefund(any(), any())).thenThrow(new ApiConnectionException("timeout"));
        BidSupplementEntity refunded = new BidSupplementEntity();
        refunded.setCommissionStatus(CommissionStatus.REFUNDED);
        when(ledger.find(s.getId())).thenReturn(Optional.of(s), Optional.of(refunded));

        assertThat(refunds.settle(s.getId())).isFalse();

        verifyNoInteractions(alerts);
        verify(ledger, never()).touch(any());
        verify(ledger, never()).auditOnly(any(), any(), any());
    }

    @Test
    void carte_plusRienAReserver_rien() {
        found();
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        s.setCommissionStatus(CommissionStatus.CHARGED);
        when(ledger.cardRefundClaim(s.getId())).thenReturn(Optional.empty());

        assertThat(refunds.settle(s.getId())).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    void troisDsEnAttente_paymentIntentAnnulee() throws Exception {
        found();
        s.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        s.setCommissionPaymentIntentId("pi_c");
        when(ledger.commissionFailed(s.getId(), "pi_c", "abandoned")).thenReturn(true);

        assertThat(refunds.settle(s.getId())).isTrue();
        verify(gateway).cancelPaymentIntent("pi_c");
    }

    @Test
    void troisDsAboutieEntreTemps_commissionEnregistreePuisRendue() throws Exception {
        found();
        s.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        s.setCommissionPaymentIntentId("pi_c");
        doThrow(new InvalidRequestException("already succeeded", null, "req", null, 400, null))
                .when(gateway).cancelPaymentIntent("pi_c");
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(view("succeeded"));
        when(ledger.commissionCharged(s.getId(), "pi_c", "cancel-refused"))
                .thenReturn(BidSupplementCashLedger.ChargeOutcome.LATE_REFUND);

        assertThat(refunds.settle(s.getId())).isTrue();
        // Le remboursement part de l'événement publié par le registre, pas d'un appel direct concurrent.
        verify(gateway, never()).createRefund(any(), any(), any());
    }

    @Test
    void annulationRefusee_paymentIntentDejaAnnulee_ouAutreStatut_ouRelectureEnEchec() throws Exception {
        found();
        s.setCommissionStatus(null);
        s.setCommissionPaymentIntentId("pi_c");
        doThrow(new InvalidRequestException("no", null, "req", null, 400, null)).when(gateway).cancelPaymentIntent("pi_c");
        when(gateway.retrievePaymentIntent("pi_c")).thenReturn(view("canceled"), view("processing"))
                .thenThrow(new ApiConnectionException("timeout"));
        when(ledger.commissionFailed(s.getId(), "pi_c", "canceled")).thenReturn(true);

        assertThat(refunds.settle(s.getId())).isTrue();
        assertThat(refunds.settle(s.getId())).isFalse();
        assertThat(refunds.settle(s.getId())).isFalse();
        verify(ledger, org.mockito.Mockito.times(2)).touch(s.getId());
    }

    @Test
    void rienADefaire() {
        when(ledger.find(any())).thenReturn(Optional.empty());
        assertThat(refunds.settle(UUID.randomUUID())).isFalse();

        found();
        s.setStatus(BidSupplementStatus.PROPOSED);
        assertThat(refunds.settle(s.getId())).as("demande encore vivante").isFalse();
        s.setStatus(BidSupplementStatus.DECLINED);
        s.setRail(BidSupplementRail.STRIPE);
        assertThat(refunds.settle(s.getId())).as("autre rail").isFalse();
        s.setRail(BidSupplementRail.CASH);
        s.setCommissionStatus(CommissionStatus.FAILED);
        assertThat(refunds.settle(s.getId())).as("rien prélevé").isFalse();
        s.setCommissionStatus(null);
        assertThat(refunds.settle(s.getId())).as("taux nul ou solde sans PI").isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    void ecouteur_railEspecesSeulement_etEchecAvale() {
        BidSupplementCashRefunds mocked = mock(BidSupplementCashRefunds.class);
        BidSupplementCashListener listener = new BidSupplementCashListener(mocked);
        UUID id = UUID.randomUUID();

        listener.onClosed(new BidSupplementDeclinedEvent(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BidSupplementRail.CASH, BidSupplementStatus.PROPOSED));
        listener.onClosed(new BidSupplementDeclinedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
        when(mocked.settle(any())).thenThrow(new IllegalStateException("boom"));
        listener.onRefundRequested(new BidSupplementRefundRequestedEvent(id, UUID.randomUUID(), BidSupplementRail.CASH,
                BidSupplementStatus.PAID, "PARCEL_REFUSED"));
        listener.onRefundRequested(new BidSupplementRefundRequestedEvent(UUID.randomUUID(), UUID.randomUUID(),
                BidSupplementRail.PAWAPAY, BidSupplementStatus.PAID, "PARCEL_REFUSED"));

        verify(mocked, org.mockito.Mockito.times(2)).settle(id);
    }

    private void chargedByCard() {
        found();
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        s.setCommissionStatus(CommissionStatus.CHARGED);
        s.setCommissionPaymentIntentId("pi_c");
        when(ledger.cardRefundClaim(s.getId())).thenReturn(Optional.of(
                new BidSupplementCashLedger.CardRefundClaim(s.getId(), s.getBidId(), s.getTravelerId(), "pi_c")));
    }

    private static PaymentIntentView view(String status) {
        return new PaymentIntentView("pi_c", status, 110L, "eur", null, null, List.of("card"));
    }
}
