package com.yadony.api.payments.supplement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import com.yadony.api.admin.AdminAlertEscalator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branche des webhooks du supplément : routage par métadonnée (PaymentIntent) ou par recherche en base (charge). */
@ExtendWith(MockitoExtension.class)
class BidSupplementStripeWebhookRouterTest {

    @Mock BidSupplementCardRail rail;
    @Mock BidSupplementCardLedger ledger;
    @Mock BidSupplementCashRail cashRail;
    @Mock BidSupplementCashLedger cashLedger;
    @Mock BidSupplementRepository supplements;
    @Mock AdminAlertEscalator alerts;
    @Mock StripeSupplementGateway gateway;

    private BidSupplementStripeWebhookRouter router;
    private BidSupplementEntity s;

    @BeforeEach
    void setUp() {
        router = new BidSupplementStripeWebhookRouter(rail, ledger, cashRail, cashLedger, supplements, alerts, gateway,
                new ObjectMapper());
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(UUID.randomUUID());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(BidSupplementStatus.PAID);
        s.setStripePaymentIntentId("pi_s");
    }

    static Event event(String type, String objectJson) {
        return ApiResource.GSON.fromJson("{\"id\":\"evt_1\",\"object\":\"event\",\"api_version\":\"" + com.stripe.Stripe.API_VERSION
                + "\",\"type\":\"" + type
                + "\",\"data\":{\"object\":" + objectJson + "}}", Event.class);
    }

    static String supplementPi(UUID supplementId, String status) {
        return "{\"id\":\"pi_s\",\"object\":\"payment_intent\",\"status\":\"" + status + "\","
                + "\"latest_charge\":\"ch_s\",\"last_payment_error\":{\"code\":\"card_declined\"},"
                + "\"metadata\":{\"bid_supplement_id\":\"" + supplementId + "\",\"scope\":\"BID_SUPPLEMENT\"}}";
    }

    @Test
    void paymentIntentReussie_duSupplement_routeeVersLeRail() {
        assertThat(router.handle(event("payment_intent.succeeded", supplementPi(s.getId(), "succeeded")))).isTrue();
        verify(rail).onPaymentSucceeded(s.getId(), "pi_s", "ch_s");
    }

    @Test
    void paymentIntentEchoueeOuAnnulee_traceSeulement() {
        assertThat(router.handle(event("payment_intent.payment_failed",
                supplementPi(s.getId(), "requires_payment_method")))).isTrue();
        assertThat(router.handle(event("payment_intent.canceled", supplementPi(s.getId(), "canceled")))).isTrue();
        assertThat(router.handle(event("payment_intent.amount_capturable_updated",
                supplementPi(s.getId(), "requires_capture")))).isTrue();

        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_PAYMENT_FAILED"), anyMap());
        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_PI_CANCELED"), anyMap());
        verifyNoInteractions(rail);
    }

    @Test
    void paymentIntentSansMetadonneeDeSupplement_nonRoutee() {
        assertThat(router.handle(event("payment_intent.succeeded",
                "{\"id\":\"pi_x\",\"metadata\":{\"wallet_topup\":\"true\"}}"))).isFalse();
        assertThat(router.handle(event("payment_intent.succeeded",
                "{\"id\":\"pi_x\",\"metadata\":{\"bid_supplement_id\":\"\"}}"))).isFalse();
        verifyNoInteractions(rail, ledger);
    }

    @Test
    void metadonneeIllisible_avaleeSansJamaisAtteindreLesTraitementsDuColis() {
        assertThat(router.handle(event("payment_intent.succeeded",
                "{\"id\":\"pi_x\",\"metadata\":{\"bid_supplement_id\":\"pas-un-uuid\"}}"))).isTrue();
        verifyNoInteractions(rail, ledger);
    }

    @Test
    void chargeRembourseeEntierement_duSupplement_rembourse() {
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));
        when(ledger.completeRefund(s.getId(), "re_1", "webhook")).thenReturn(true);

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_s\",\"payment_intent\":\"pi_s\","
                + "\"amount\":2110,\"amount_refunded\":2110,\"refunds\":{\"data\":[{\"id\":\"re_1\"}]}}"))).isTrue();

        verify(ledger).completeRefund(s.getId(), "re_1", "webhook");
        verifyNoInteractions(alerts);
    }

    @Test
    void chargeRembourseeSansListeDesRefunds_identifiantReluChezStripe() throws Exception {
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));
        when(gateway.latestRefund("pi_s")).thenReturn(Optional.of("re_lu"));

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_s\",\"payment_intent\":\"pi_s\","
                + "\"amount\":2110,\"amount_refunded\":2110}"))).isTrue();

        verify(ledger).completeRefund(s.getId(), "re_lu", "webhook");
    }

    @Test
    void chargeRembourseeSansListe_lectureStripeEnEchec_rembourseQuandMeme() throws Exception {
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));
        when(gateway.latestRefund("pi_s")).thenThrow(new com.stripe.exception.ApiConnectionException("timeout"));

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_s\",\"payment_intent\":\"pi_s\","
                + "\"amount\":2110,\"amount_refunded\":2110}"))).isTrue();

        verify(ledger).completeRefund(s.getId(), null, "webhook");
    }

    @Test
    void refundEchoueDuSupplement_plusFaussementRembourse_etAlerte() {
        s.setStatus(BidSupplementStatus.REFUNDED);
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));
        when(ledger.refundFailed(s.getId(), "re_1")).thenReturn(true);

        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_1\",\"object\":\"refund\","
                + "\"status\":\"failed\",\"failure_reason\":\"expired_or_canceled_card\","
                + "\"payment_intent\":\"pi_s\",\"charge\":\"ch_s\"}"))).isTrue();

        verify(ledger).refundFailed(s.getId(), "re_1");
        verify(alerts).raiseOnce(eq("SUPP_REFUND_FAILED_" + s.getId()), anyString(), anyMap());
    }

    @Test
    void refundMisAJourSansEchec_duSupplement_avaleSansEffet_inconnu_laisse() {
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_1\",\"status\":\"succeeded\","
                + "\"payment_intent\":\"pi_s\"}"))).isTrue();
        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_2\",\"status\":\"failed\","
                + "\"payment_intent\":\"pi_autre\"}"))).isFalse();
        verify(ledger, never()).refundFailed(any(), any());
        verifyNoInteractions(alerts);
    }

    @Test
    void chargeRembourseeApresVersement_alerte() {
        s.setStatus(BidSupplementStatus.PAID_OUT);
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.empty());
        when(supplements.findByStripeChargeId("ch_s")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_s\",\"payment_intent\":\"pi_s\","
                + "\"amount\":2110,\"amount_refunded\":2110}"))).isTrue();

        verify(ledger).completeRefund(s.getId(), null, "webhook");
        verify(alerts).raiseOnce(eq("SUPP_REFUND_PAID_OUT_" + s.getId()), anyString(), anyMap());
    }

    @Test
    void chargeRembourseeRejouee_sansAlerteNiSecondEffet() {
        s.setStatus(BidSupplementStatus.REFUNDED);
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_s\",\"payment_intent\":\"pi_s\","
                + "\"amount\":2110,\"amount_refunded\":2110,\"refunds\":{\"data\":[]}}"))).isTrue();
        verifyNoInteractions(alerts);
    }

    @Test
    void remboursementPartiel_alerteSansTransition() {
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_s\",\"payment_intent\":\"pi_s\","
                + "\"amount\":2110,\"amount_refunded\":1000}"))).isTrue();

        verify(ledger, never()).completeRefund(any(), any(), any());
        verify(alerts).raiseOnce(eq("SUPP_PARTIAL_REFUND_" + s.getId()), anyString(), anyMap());
    }

    @Test
    void chargeInconnue_laisseeAuxTraitementsDuColis() {
        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_x\",\"payment_intent\":\"pi_x\","
                + "\"amount\":100,\"amount_refunded\":100}"))).isFalse();
        assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_1\",\"charge\":\"ch_x\"}")))
                .isFalse();
        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_x\"}"))).isFalse();
        verifyNoInteractions(ledger, alerts);
    }

    @Test
    void litigeSurLaChargeDuSupplement_alerteAdminJamaisChargebackService() {
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.dispute.created",
                "{\"id\":\"dp_1\",\"charge\":\"ch_s\",\"payment_intent\":\"pi_s\",\"status\":\"needs_response\"}")))
                .isTrue();
        assertThat(router.handle(event("charge.dispute.closed",
                "{\"id\":\"dp_1\",\"charge\":\"ch_s\",\"payment_intent\":\"pi_s\",\"status\":\"won\"}"))).isTrue();

        verify(alerts).raiseOnce(eq("SUPP_CHARGEBACK_" + s.getId()), anyString(), anyMap());
        verify(ledger, org.mockito.Mockito.times(2)).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_CHARGEBACK"), anyMap());
    }

    @Test
    void autresEvenements_ouObjetIllisible_nonRoutes() {
        assertThat(router.handle(event("account.updated", "{\"id\":\"acct_1\"}"))).isFalse();
        assertThat(router.handle(event("transfer.created", "{\"id\":\"tr_1\"}"))).isFalse();
        Event noType = event("payment_intent.succeeded", "{}");
        noType.setType(null);
        assertThat(router.handle(noType)).isFalse();

        Event broken = mock(Event.class);
        when(broken.getType()).thenReturn("payment_intent.succeeded");
        when(broken.getDataObjectDeserializer()).thenThrow(new IllegalStateException("payload"));
        assertThat(router.handle(broken)).isFalse();
        verifyNoInteractions(rail, ledger, supplements);
    }

    // ── Commission d'un supplément espèces (B4) ──────────────────────────────

    static String commissionPi(UUID supplementId, String status) {
        return "{\"id\":\"pi_c\",\"object\":\"payment_intent\",\"status\":\"" + status + "\","
                + "\"last_payment_error\":{\"code\":\"authentication_failed\"},"
                + "\"metadata\":{\"bid_supplement_id\":\"" + supplementId + "\","
                + "\"commission_purpose\":\"bid_supplement_commission\",\"scope\":\"BID_SUPPLEMENT_COMMISSION\"}}";
    }

    @Test
    void paymentIntentDeCommission_routeeVersLeRailEspeces_jamaisLeRailCarte() {
        assertThat(router.handle(event("payment_intent.succeeded", commissionPi(s.getId(), "succeeded")))).isTrue();
        assertThat(router.handle(event("payment_intent.payment_failed",
                commissionPi(s.getId(), "requires_payment_method")))).isTrue();

        verify(cashRail).onCommissionWebhook("payment_intent.succeeded", s.getId(), "pi_c", "authentication_failed");
        verify(cashRail).onCommissionWebhook("payment_intent.payment_failed", s.getId(), "pi_c",
                "authentication_failed");
        verifyNoInteractions(rail, ledger);
    }

    @Test
    void chargeDeCommissionRembourseeEntierement_commissionRendue() {
        s.setRail(BidSupplementRail.CASH);
        when(supplements.findByCommissionPaymentIntentId("pi_c")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"payment_intent\":\"pi_c\","
                + "\"amount\":110,\"amount_refunded\":110,\"refunds\":{\"data\":[{\"id\":\"re_c\"}]}}"))).isTrue();
        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"payment_intent\":\"pi_c\","
                + "\"amount\":110,\"amount_refunded\":110}"))).isTrue();

        verify(cashLedger).cardRefunded(s.getId(), "re_c", "webhook");
        verify(cashLedger).cardRefunded(s.getId(), null, "webhook");
        verifyNoInteractions(ledger, alerts);
    }

    @Test
    void chargeDeCommissionRembourseePartiellement_alerte() {
        when(supplements.findByCommissionPaymentIntentId("pi_c")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"payment_intent\":\"pi_c\","
                + "\"amount\":110,\"amount_refunded\":50}"))).isTrue();

        verify(cashLedger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_COMMISSION_PARTIAL_REFUND"), anyMap());
        verify(alerts).raiseOnce(eq("SUPP_COMM_PARTIAL_REF_" + s.getId()), anyString(), anyMap());
    }

    @Test
    void refundDeCommissionEchoue_alerte_autresMisesAJourAvalees() {
        when(supplements.findByCommissionPaymentIntentId("pi_c")).thenReturn(Optional.of(s));
        when(cashLedger.commissionRefundFailed(s.getId(), "re_c")).thenReturn(true);

        assertThat(router.handle(event("charge.refund.updated",
                "{\"id\":\"re_c\",\"payment_intent\":\"pi_c\",\"status\":\"failed\"}"))).isTrue();
        assertThat(router.handle(event("charge.refund.updated",
                "{\"id\":\"re_c\",\"payment_intent\":\"pi_c\",\"status\":\"succeeded\"}"))).isTrue();

        verify(alerts).raiseOnce(eq("SUPP_COMM_REFUND_FAIL_" + s.getId()), anyString(), anyMap());
        verify(cashLedger, org.mockito.Mockito.times(1)).commissionRefundFailed(any(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    void litigeSurLaChargeDeCommission_alerte() {
        when(supplements.findByCommissionPaymentIntentId("pi_c")).thenReturn(Optional.of(s));

        assertThat(router.handle(event("charge.dispute.created",
                "{\"id\":\"dp_c\",\"charge\":\"ch_c\",\"payment_intent\":\"pi_c\",\"status\":\"needs_response\"}")))
                .isTrue();

        verify(alerts).raiseOnce(eq("SUPP_CHARGEBACK_" + s.getId()), anyString(), anyMap());
    }

    @Test
    void chargeInconnue_nonRoutee() {
        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_x\",\"amount\":100}"))).isFalse();
        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_x\",\"status\":\"failed\"}")))
                .isFalse();
        verifyNoInteractions(cashLedger, alerts);
    }
}
