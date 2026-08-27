package com.yadony.api.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProBillingStripeWebhookHandler")
class ProBillingStripeWebhookHandlerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SUBSCRIPTION_ID = "sub_abc";

    @Mock ProSubscriptionRepository repository;
    @Mock ProSubscriptionService subscriptionService;

    private ProBillingStripeWebhookHandler handler() {
        return new ProBillingStripeWebhookHandler(repository, subscriptionService, new ObjectMapper());
    }

    /** Construit un Event Stripe à partir d'un JSON, comme le fait StripeEventDispatcher. */
    private static Event event(String type, String dataObjectJson) {
        String json = "{\"id\":\"evt_1\",\"object\":\"event\",\"type\":\"" + type
                + "\",\"data\":{\"object\":" + dataObjectJson + "}}";
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    private ProSubscriptionEntity existingSubscription(ProSubscriptionStatus status) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        ReflectionTestUtils.setField(sub, "id", UUID.randomUUID());
        sub.setUserId(USER_ID);
        sub.setStatus(status);
        sub.setSource(ProSubscriptionSource.STRIPE);
        sub.setStripeSubscriptionId(SUBSCRIPTION_ID);
        return sub;
    }

    @Test
    @DisplayName("supports n'accepte que les cinq types attendus")
    void supportsOnlyBillingEvents() {
        ProBillingStripeWebhookHandler h = handler();
        assertThat(h.supports("checkout.session.completed")).isTrue();
        assertThat(h.supports("invoice.paid")).isTrue();
        assertThat(h.supports("invoice.payment_failed")).isTrue();
        assertThat(h.supports("customer.subscription.updated")).isTrue();
        assertThat(h.supports("customer.subscription.deleted")).isTrue();

        // Revendiqués par PaymentStripeWebhookHandler : ne jamais les intercepter.
        assertThat(h.supports("payment_intent.succeeded")).isFalse();
        assertThat(h.supports("charge.refunded")).isFalse();
    }

    @Test
    @DisplayName("checkout.session.completed active l'abonnement pour l'utilisateur référencé")
    void checkoutCompletedActivates() {
        String data = "{\"id\":\"cs_1\",\"client_reference_id\":\"" + USER_ID
                + "\",\"customer\":\"cus_1\",\"subscription\":\"" + SUBSCRIPTION_ID
                + "\",\"metadata\":{\"billing_cycle\":\"MONTHLY\"}}";

        handler().handle(event("checkout.session.completed", data));

        verify(subscriptionService).activateFromStripe(
                eq(USER_ID), eq("cus_1"), eq(SUBSCRIPTION_ID),
                eq(BillingCycle.MONTHLY), any(Instant.class));
    }

    @Test
    @DisplayName("checkout.session.completed sans client_reference_id est ignoré sans exception")
    void checkoutWithoutUserReferenceIsIgnored() {
        String data = "{\"id\":\"cs_2\",\"customer\":\"cus_1\",\"subscription\":\"sub_x\"}";

        handler().handle(event("checkout.session.completed", data));

        // Lever ici déclencherait sept relances puis une mise en DEAD_LETTER.
        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("invoice.paid renouvelle l'abonnement retrouvé")
    void invoicePaidRenews() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.PAST_DUE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"in_1\",\"subscription\":\"" + SUBSCRIPTION_ID
                + "\",\"period_end\":1788000000}";

        handler().handle(event("invoice.paid", data));

        verify(subscriptionService).renew(eq(sub), any(Instant.class));
    }

    @Test
    @DisplayName("invoice.payment_failed passe en impayé sans couper l'accès")
    void invoiceFailedMarksPastDue() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"in_2\",\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("invoice.payment_failed", data));

        verify(subscriptionService).markPastDue(sub);
    }

    @Test
    @DisplayName("un abonnement inconnu est ignoré sans exception")
    void unknownSubscriptionIsIgnored() {
        when(repository.findByStripeSubscriptionId("sub_inconnu")).thenReturn(Optional.empty());
        String data = "{\"id\":\"in_3\",\"subscription\":\"sub_inconnu\"}";

        handler().handle(event("invoice.paid", data));

        verify(subscriptionService, never()).renew(any(), any());
    }

    @Test
    @DisplayName("customer.subscription.updated reporte la résiliation programmée")
    void subscriptionUpdatedReportsCancelAtPeriodEnd() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\",\"cancel_at_period_end\":true}";

        handler().handle(event("customer.subscription.updated", data));

        verify(subscriptionService).markCancelAtPeriodEnd(sub, true);
    }

    @Test
    @DisplayName("customer.subscription.deleted ferme l'abonnement")
    void subscriptionDeletedCancels() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("customer.subscription.deleted", data));

        verify(subscriptionService).cancel(sub);
    }

    @Test
    @DisplayName("un abonnement déjà fermé n'est pas fermé une seconde fois")
    void alreadyClosedSubscriptionIsNotCancelledAgain() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.CANCELED);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("customer.subscription.deleted", data));

        // Stripe rejoue ses événements : une seconde fermeture réécrirait audit_log.
        verify(subscriptionService, never()).cancel(any());
    }
}
