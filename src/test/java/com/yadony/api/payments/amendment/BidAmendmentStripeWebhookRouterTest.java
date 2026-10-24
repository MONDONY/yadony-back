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
    @Mock BidAmendmentCardRail cardRail;
    @Mock BidAmendmentCardLedger cardLedger;
    @Mock BidAmendmentRepository amendments;
    @Mock AdminAlertEscalator alerts;
    @Mock com.yadony.api.payments.supplement.StripeSupplementGateway gateway;

    private BidAmendmentStripeWebhookRouter router;
    private BidAmendmentEntity a;

    @BeforeEach
    void setUp() {
        router = new BidAmendmentStripeWebhookRouter(cashRail, cashLedger, cardRail, cardLedger, amendments, alerts,
                gateway,
                new ObjectMapper());
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
                + amendmentId + "\",\"commission_purpose\":\"bid_amendment_commission\",\"commission_attempt\":\"7\"}}";
    }

    @Test
    @DisplayName("PaymentIntent de commission : routée vers le rail espèces, avec le code d'échec")
    void commissionPaymentIntent_routedToCashRail() {
        assertThat(router.handle(event("payment_intent.succeeded", commissionPi(a.getId())))).isTrue();
        assertThat(router.handle(event("payment_intent.payment_failed", commissionPi(a.getId())))).isTrue();

        verify(cashRail).onCommissionWebhook("payment_intent.succeeded", a.getId(), "pi_c", "7", "card_declined");
        verify(cashRail).onCommissionWebhook("payment_intent.payment_failed", a.getId(), "pi_c", "7", "card_declined");
    }

    @Test
    @DisplayName("PaymentIntent du montant carte d'une modification : routée vers le rail carte, jamais le colis")
    void cardPaymentIntent_routedToCardRail() {
        assertThat(router.handle(event("payment_intent.amount_capturable_updated", "{\"id\":\"pi_m\","
                + "\"object\":\"payment_intent\",\"status\":\"requires_capture\",\"metadata\":{\"bid_amendment_id\":\""
                + a.getId() + "\",\"scope\":\"BID_AMENDMENT\"}}"))).isTrue();
        assertThat(router.handle(event("payment_intent.succeeded", "{\"id\":\"pi_m\",\"object\":\"payment_intent\","
                + "\"status\":\"succeeded\",\"latest_charge\":\"ch_m\",\"metadata\":{\"bid_amendment_id\":\""
                + a.getId() + "\"}}"))).isTrue();

        verify(cardRail).onPaymentIntentWebhook("payment_intent.amount_capturable_updated", a.getId(), "pi_m",
                "requires_capture", null, "unknown");
        verify(cardRail).onPaymentIntentWebhook("payment_intent.succeeded", a.getId(), "pi_m", "succeeded", "ch_m",
                "unknown");
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
        a.setCommissionPaymentIntentId("pi_c");
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

    // ── PaymentIntent d'un essai précédent : retrouvée par la métadonnée ─────

    private String staleCharge(String extra) {
        return "{\"id\":\"ch_old\",\"object\":\"charge\",\"payment_intent\":\"pi_old\"," + extra
                + "\"metadata\":{\"bid_amendment_id\":\"" + a.getId()
                + "\",\"commission_purpose\":\"bid_amendment_commission\"}}";
    }

    private void currentPaymentIntentIsAnother() {
        a.setCommissionPaymentIntentId("pi_new");
        when(amendments.findByCommissionPaymentIntentId("pi_old")).thenReturn(Optional.empty());
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
    }

    @Test
    @DisplayName("charge remboursée d'un essai précédent : retrouvée par la métadonnée, tracée, la commission courante n'est pas touchée")
    void staleCharge_refunded_auditedOnly() {
        currentPaymentIntentIsAnother();

        assertThat(router.handle(event("charge.refunded", staleCharge("\"amount\":360,\"amount_refunded\":360,"))))
                .isTrue();

        verify(cashLedger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_STALE_COMMISSION_REFUND_CONFIRMED"), anyMap());
        verify(cashLedger, never()).cardRefunded(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("Refund échoué d'une commission d'un essai précédent : alerte, la commission courante n'est pas touchée")
    void staleRefund_failed_alertsOnly() {
        currentPaymentIntentIsAnother();

        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_1\",\"object\":\"refund\","
                + "\"payment_intent\":\"pi_old\",\"status\":\"failed\",\"failure_reason\":\"lost_or_stolen_card\","
                + "\"metadata\":{\"bid_amendment_id\":\"" + a.getId()
                + "\",\"commission_purpose\":\"bid_amendment_commission\"}}"))).isTrue();

        verify(alerts).raiseOnce(eq("AMEND_STALE_RFD_FAIL_re_1"), anyString(), anyMap());
        verify(cashLedger, never()).commissionRefundFailed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("litige sur la charge d'un essai précédent : métadonnée relue chez Stripe, alerte")
    void staleDispute_metadataReadFromStripe() throws Exception {
        currentPaymentIntentIsAnother();
        when(gateway.retrieveMetadata("pi_old")).thenReturn(java.util.Map.of("bid_amendment_id",
                a.getId().toString(), "commission_purpose", "bid_amendment_commission"));

        assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_1\",\"object\":\"dispute\","
                + "\"payment_intent\":\"pi_old\",\"status\":\"needs_response\"}"))).isTrue();

        verify(cashLedger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_CHARGEBACK"), anyMap());
        verify(alerts).raiseOnce(eq("AMEND_CHARGEBACK_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("litige dont la PaymentIntent n'est pas une commission, illisible ou d'un autre type : passe la main")
    void dispute_notACommission_passesThrough() throws Exception {
        when(amendments.findByCommissionPaymentIntentId(anyString())).thenReturn(Optional.empty());
        when(gateway.retrieveMetadata("pi_bid")).thenReturn(java.util.Map.of("bid_id", "b1"));
        when(gateway.retrieveMetadata("pi_down")).thenThrow(new com.stripe.exception.ApiConnectionException("timeout"));
        when(gateway.retrieveMetadata("pi_bad")).thenReturn(java.util.Map.of("bid_amendment_id", "pas-un-uuid",
                "commission_purpose", "bid_amendment_commission"));

        for (String pi : java.util.List.of("pi_bid", "pi_down", "pi_bad")) {
            assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_x\",\"object\":\"dispute\","
                    + "\"payment_intent\":\"" + pi + "\"}"))).isFalse();
        }
        assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_y\",\"object\":\"dispute\"}")))
                .isFalse();
        verifyNoInteractions(cashLedger, alerts);
    }

    @Test
    @DisplayName("charge dont la métadonnée est celle d'un autre usage ou d'une modification inconnue : passe la main")
    void charge_foreignOrUnknownMetadata_passesThrough() {
        when(amendments.findByCommissionPaymentIntentId("pi_old")).thenReturn(Optional.empty());
        when(amendments.findById(a.getId())).thenReturn(Optional.empty());

        assertThat(router.handle(event("charge.refunded", staleCharge("\"amount\":100,\"amount_refunded\":100,"))))
                .isFalse();
        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_b\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_old\",\"amount\":100,\"amount_refunded\":100,"
                + "\"metadata\":{\"bid_amendment_id\":\"" + a.getId() + "\"}}"))).isFalse();
        verifyNoInteractions(cashLedger, alerts);
    }

    /** Essai échoué : la PaymentIntent reste enregistrée mais n'est plus la courante (PROPOSED + FAILED). */
    private void registeredPaymentIntentOfFailedAttempt() {
        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setCommissionStatus(com.yadony.api.payments.cash.CommissionStatus.FAILED);
        a.setCommissionPaymentIntentId("pi_c");
        when(amendments.findByCommissionPaymentIntentId("pi_c")).thenReturn(Optional.of(a));
    }

    @Test
    @DisplayName("Refund échoué d'une PaymentIntent enregistrée d'un essai échoué : alerte hors essai, commission non touchée")
    void failedAttemptPaymentIntent_refundFailed_alerts() {
        registeredPaymentIntentOfFailedAttempt();

        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_9\",\"object\":\"refund\","
                + "\"payment_intent\":\"pi_c\",\"status\":\"failed\",\"failure_reason\":\"expired_or_canceled_card\"}")))
                .isTrue();

        verify(alerts).raiseOnce(eq("AMEND_STALE_RFD_FAIL_re_9"), anyString(), anyMap());
        verify(cashLedger, never()).commissionRefundFailed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("charge remboursée d'une PaymentIntent enregistrée d'un essai échoué : confirmation tracée, commission non touchée")
    void failedAttemptPaymentIntent_refunded_confirmationAudited() {
        registeredPaymentIntentOfFailedAttempt();

        assertThat(router.handle(event("charge.refunded", "{\"id\":\"ch_c\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_c\",\"amount\":360,\"amount_refunded\":360,"
                + "\"refunds\":{\"data\":[{\"id\":\"re_9\"}]}}"))).isTrue();

        verify(cashLedger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_STALE_COMMISSION_REFUND_CONFIRMED"), anyMap());
        verify(cashLedger, never()).cardRefunded(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(alerts);
    }

    // ── Montant carte de l'expéditeur ────────────────────────────────────────

    private void cardLookup() {
        a.setRail(BidSupplementRail.STRIPE);
        a.setStatus(BidAmendmentStatus.REFUND_PENDING);
        a.setStripePaymentIntentId("pi_card");
        when(amendments.findByStripePaymentIntentId("pi_card")).thenReturn(Optional.of(a));
    }

    private static String cardCharge(long refunded) {
        return "{\"id\":\"ch_card\",\"object\":\"charge\",\"payment_intent\":\"pi_card\",\"amount\":3360,"
                + "\"amount_refunded\":" + refunded + ",\"refunds\":{\"object\":\"list\",\"data\":[{\"id\":\"re_c\","
                + "\"object\":\"refund\"}]}}";
    }

    @Test
    @DisplayName("montant carte remboursé en entier : enregistré par le registre carte, jamais la commission")
    void cardFullyRefunded_recordedByCardLedger() {
        cardLookup();
        when(cardLedger.refunded(a.getId(), "re_c", "webhook")).thenReturn(BidAmendmentCardLedger.RefundOutcome.REFUNDED);

        assertThat(router.handle(event("charge.refunded", cardCharge(3360)))).isTrue();

        verify(cardLedger).refunded(a.getId(), "re_c", "webhook");
        verifyNoInteractions(cashLedger, alerts);
    }

    @Test
    @DisplayName("montant carte remboursé alors que la modification vit encore (Dashboard) : alerte")
    void cardRefundedWhileLive_alerts() {
        cardLookup();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        when(cardLedger.refunded(a.getId(), "re_c", "webhook")).thenReturn(BidAmendmentCardLedger.RefundOutcome.LIVE);

        assertThat(router.handle(event("charge.refunded", cardCharge(3360)))).isTrue();

        verify(alerts).raiseOnce(eq("AMEND_CARD_REF_LIVE_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("montant carte remboursé en partie : trace et alerte, statut inchangé")
    void cardPartiallyRefunded_auditsAndAlerts() {
        cardLookup();

        assertThat(router.handle(event("charge.refunded", cardCharge(1000)))).isTrue();

        verify(cardLedger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_PARTIAL_REFUND"), anyMap());
        verify(alerts).raiseOnce(eq("AMEND_CARD_PARTIAL_REF_" + a.getId()), anyString(), anyMap());
        verify(cardLedger, never()).refunded(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Refund du montant carte échoué chez Stripe : de nouveau à rembourser, alerte ; autre mise à jour avalée")
    void cardRefundFailed_marksAndAlerts() {
        cardLookup();
        when(cardLedger.refundFailed(a.getId(), "re_c")).thenReturn(true);

        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_c\",\"object\":\"refund\","
                + "\"payment_intent\":\"pi_card\",\"status\":\"failed\",\"failure_reason\":\"expired_or_canceled_card\"}")))
                .isTrue();
        assertThat(router.handle(event("charge.refund.updated", "{\"id\":\"re_c\",\"object\":\"refund\","
                + "\"payment_intent\":\"pi_card\",\"status\":\"succeeded\"}"))).isTrue();

        verify(alerts).raiseOnce(eq("AMEND_CARD_RFD_FAILED_" + a.getId()), anyString(), anyMap());
        verify(cardLedger, org.mockito.Mockito.times(1)).refundFailed(a.getId(), "re_c");
        verifyNoInteractions(cashLedger);
    }

    @Test
    @DisplayName("litige sur le montant carte : métadonnée scope relue chez Stripe, trace et alerte, jamais le colis")
    void cardDispute_resolvedFromStripeMetadata() throws Exception {
        a.setRail(BidSupplementRail.STRIPE);
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        when(gateway.retrieveMetadata("pi_unknown")).thenReturn(java.util.Map.of("bid_amendment_id",
                a.getId().toString(), "scope", "BID_AMENDMENT", "bid_id", a.getBidId().toString()));

        assertThat(router.handle(event("charge.dispute.created", "{\"id\":\"dp_c\",\"object\":\"dispute\","
                + "\"payment_intent\":\"pi_unknown\",\"status\":\"needs_response\"}"))).isTrue();

        verify(cardLedger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_CHARGEBACK"), anyMap());
        verify(alerts).raiseOnce(eq("AMEND_CHARGEBACK_" + a.getId()), anyString(), anyMap());
        verifyNoInteractions(cashLedger);
    }

    @Test
    @DisplayName("métadonnée de commission pointant une modification carte (ou l'inverse) : passe la main")
    void metadataOfTheOtherRail_passesThrough() {
        a.setRail(BidSupplementRail.STRIPE);
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));

        assertThat(router.handle(event("charge.refunded", staleCharge("\"amount\":100,\"amount_refunded\":100,"))))
                .as("commission_purpose sur une modification carte").isFalse();
        verifyNoInteractions(cashLedger, cardLedger, alerts);
    }
}
