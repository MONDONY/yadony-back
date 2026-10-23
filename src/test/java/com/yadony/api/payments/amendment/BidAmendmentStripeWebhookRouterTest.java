package com.yadony.api.payments.amendment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branche des webhooks des modifications : commission espèces (métadonnée) et charges (recherche en base). */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidAmendmentStripeWebhookRouter")
class BidAmendmentStripeWebhookRouterTest {

    @Mock BidAmendmentCashRail cashRail;
    @Mock BidAmendmentCashLedger cashLedger;
    @Mock BidAmendmentRepository amendments;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentStripeWebhookRouter router;
    private BidAmendmentEntity a;

    @BeforeEach
    void setUp() {
        router = new BidAmendmentStripeWebhookRouter(cashRail, cashLedger, amendments, alerts, new ObjectMapper());
        a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(UUID.randomUUID());
        a.setRail(BidSupplementRail.CASH);
        a.setStatus(BidAmendmentStatus.CANCELLED);
    }

    static Event event(String type, String objectJson) {
        return ApiResource.GSON.fromJson("{\"id\":\"evt_1\",\"object\":\"event\",\"api_version\":\""
                + com.stripe.Stripe.API_VERSION + "\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson
                + "}}", Event.class);
    }

    private String commissionPi(UUID amendmentId) {
        return "{\"id\":\"pi_c\",\"object\":\"payment_intent\",\"status\":\"succeeded\","
                + "\"last_payment_error\":{\"code\":\"card_declined\"},\"metadata\":{\"bid_amendment_id\":\""
                + amendmentId + "\",\"commission_purpose\":\"bid_amendment_commission\"}}";
    }

    @Test
    @DisplayName("PaymentIntent de commission : routée vers le rail espèces, avec le code d'échec")
    void commissionPaymentIntent_routedToCashRail() {
        assertThat(router.handle(event("payment_intent.succeeded", commissionPi(a.getId())))).isTrue();
        assertThat(router.handle(event("payment_intent.payment_failed", commissionPi(a.getId())))).isTrue();

        verify(cashRail).onCommissionWebhook("payment_intent.succeeded", a.getId(), "pi_c", "card_declined");
        verify(cashRail).onCommissionWebhook("payment_intent.payment_failed", a.getId(), "pi_c", "card_declined");
    }

    @Test
    @DisplayName("PaymentIntent d'une modification (rail carte, B9) : avalée, jamais le colis")
    void amendmentPaymentIntent_swallowed() {
        assertThat(router.handle(event("payment_intent.succeeded", "{\"id\":\"pi_m\",\"object\":\"payment_intent\","
                + "\"metadata\":{\"bid_amendment_id\":\"" + a.getId() + "\"}}"))).isTrue();
        verifyNoInteractions(cashRail);
    }

    @Test
    @DisplayName("métadonnée de modification illisible : avalée, jamais transmise aux traitements du colis")
    void unreadableMetadata_swallowed() {
        assertThat(router.handle(event("payment_intent.succeeded", "{\"id\":\"pi_m\",\"object\":\"payment_intent\","
                + "\"metadata\":{\"bid_amendment_id\":\"pas-un-uuid\"}}"))).isTrue();
        assertThat(router.handle(event("payment_intent.succeeded", "{\"id\":\"pi_m\",\"object\":\"payment_intent\","
                + "\"metadata\":{\"commission_purpose\":\"bid_amendment_commission\"}}"))).isTrue();
        verifyNoInteractions(cashRail);
    }

    @Test
    @DisplayName("tout le reste passe la main : colis, supplément, recharge, commission du colis")
    void everythingElse_passesThrough() {
        assertThat(router.handle(event("payment_intent.succeeded", "{\"id\":\"pi_x\",\"object\":\"payment_intent\","
                + "\"metadata\":{\"commission_purpose\":\"cash_bid\",\"bid_id\":\"" + UUID.randomUUID() + "\"}}")))
                .isFalse();
        assertThat(router.handle(event("payment_intent.succeeded", "{\"id\":\"pi_x\",\"object\":\"payment_intent\","
                + "\"metadata\":{\"bid_supplement_id\":\"" + UUID.randomUUID() + "\"}}"))).isFalse();
        assertThat(router.handle(event("payment_intent.succeeded", "{\"id\":\"pi_x\",\"object\":\"payment_intent\"}")))
                .isFalse();
        assertThat(router.handle(event("account.updated", "{\"id\":\"acct_1\",\"object\":\"account\"}"))).isFalse();
        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_1\",\"object\":\"charge\"}"))).isFalse();
        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_1\",\"object\":\"refund\"}"))).isFalse();
        assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_1\",\"object\":\"dispute\"}"))).isFalse();
        verifyNoInteractions(cashRail, cashLedger, alerts);
    }

    @Test
    @DisplayName("événement sans type ou à l'objet illisible : passe la main")
    void noTypeOrUnreadableObject_passesThrough() {
        Event noType = mock(Event.class);
        assertThat(router.handle(noType)).isFalse();

        Event unreadable = mock(Event.class);
        when(unreadable.getType()).thenReturn("payment_intent.succeeded");
        when(unreadable.getDataObjectDeserializer()).thenThrow(new IllegalStateException("boom"));
        assertThat(router.handle(unreadable)).isFalse();
    }

    private void commissionLookup() {
        when(amendments.findByCommissionPaymentIntentId("pi_c")).thenReturn(Optional.of(a));
    }

    @Test
    @DisplayName("commission remboursée en entier sur une modification close : enregistrée, sans alerte")
    void commissionFullyRefunded_closedAmendment_recordedWithoutAlert() {
        commissionLookup();
        when(cashLedger.cardRefunded(a.getId(), "re_1", "webhook", false)).thenReturn(true);

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_c\",\"amount\":360,\"amount_refunded\":360,"
                + "\"refunds\":{\"data\":[{\"id\":\"re_1\"}]}}"))).isTrue();

        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("commission remboursée hors de l'app alors que la modification vit encore : alerte admin")
    void commissionFullyRefunded_liveAmendment_alerts() {
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        commissionLookup();
        when(cashLedger.cardRefunded(a.getId(), null, "webhook", false)).thenReturn(true);

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_c\",\"amount\":360,\"amount_refunded\":360}"))).isTrue();

        verify(alerts).raiseOnce(eq("AMEND_COMM_REFUND_LIVE_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("remboursement déjà enregistré (rejeu) sur une modification vivante : pas de seconde alerte")
    void commissionFullyRefunded_replay_noAlert() {
        a.setStatus(BidAmendmentStatus.PROPOSED);
        commissionLookup();
        when(cashLedger.cardRefunded(a.getId(), null, "webhook", false)).thenReturn(false);

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_c\",\"amount\":360,\"amount_refunded\":360}"))).isTrue();

        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("remboursement partiel de la commission : trace et alerte")
    void commissionPartiallyRefunded_auditsAndAlerts() {
        commissionLookup();

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_c\",\"amount\":360,\"amount_refunded\":100}"))).isTrue();

        verify(cashLedger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_COMMISSION_PARTIAL_REFUND"), anyMap());
        verify(alerts).raiseOnce(eq("AMEND_COMM_PARTIAL_REF_" + a.getId()), anyString(), anyMap());
        verify(cashLedger, never()).cardRefunded(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("Refund de commission échoué après coup : REFUND_FAILED et alerte ; autre statut : sans effet")
    void commissionRefundFailed_marksAndAlerts() {
        commissionLookup();
        when(cashLedger.commissionRefundFailed(a.getId(), "re_1")).thenReturn(true);

        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_1\",\"object\":\"refund\","
                + "\"payment_intent\":\"pi_c\",\"status\":\"failed\",\"failure_reason\":\"expired_or_canceled_card\"}")))
                .isTrue();
        verify(alerts).raiseOnce(eq("AMEND_COMM_REFUND_FAIL_" + a.getId()), anyString(), anyMap());

        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_2\",\"object\":\"refund\","
                + "\"payment_intent\":\"pi_c\",\"status\":\"succeeded\"}"))).isTrue();
        verify(cashLedger, never()).commissionRefundFailed(a.getId(), "re_2");
    }

    @Test
    @DisplayName("litige sur la charge d'une commission : trace, et alerte à l'ouverture seulement")
    void dispute_auditsAndAlertsOnCreation() {
        commissionLookup();

        assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_1\",\"object\":\"dispute\","
                + "\"payment_intent\":\"pi_c\",\"status\":\"needs_response\"}"))).isTrue();
        assertThat(router.handle(event("charge.dispute.closed", "{\"id\":\"dp_1\",\"object\":\"dispute\","
                + "\"payment_intent\":\"pi_c\",\"status\":\"lost\"}"))).isTrue();

        verify(cashLedger, org.mockito.Mockito.times(2)).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_CHARGEBACK"),
                anyMap());
        verify(alerts, org.mockito.Mockito.times(1)).raiseOnce(eq("AMEND_CHARGEBACK_" + a.getId()), anyString(),
                anyMap());
    }

    @Test
    @DisplayName("charge dont la PaymentIntent n'est celle d'aucune commission : passe la main")
    void chargeOfAnotherPaymentIntent_passesThrough() {
        when(amendments.findByCommissionPaymentIntentId("pi_other")).thenReturn(Optional.empty());

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_o\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_other\",\"amount\":100,\"amount_refunded\":100}"))).isFalse();
        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_o\",\"object\":\"refund\","
                + "\"payment_intent\":\"pi_other\",\"status\":\"failed\"}"))).isFalse();
        assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_o\",\"object\":\"dispute\","
                + "\"payment_intent\":\"pi_other\"}"))).isFalse();
        verifyNoInteractions(cashLedger, alerts);
    }
}
