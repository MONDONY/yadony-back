package com.yadony.api.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    // --- Lecture du corps de l'événement ---

    @Test
    @DisplayName("un JSON de data object illisible n'entraîne aucune relance")
    void unreadableDataObjectJsonTriggersNoRetry() throws JsonProcessingException {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.readTree(anyString()))
                .thenThrow(new JsonProcessingException("JSON invalide") {});
        ProBillingStripeWebhookHandler handler =
                new ProBillingStripeWebhookHandler(repository, subscriptionService, brokenMapper);
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\"}";

        // Lever ici déclencherait sept relances puis une mise en DEAD_LETTER.
        handler.handle(event("customer.subscription.deleted", data));

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("un événement sans objet de données, ou avec un objet vide, est ignoré sans exception")
    void missingOrEmptyDataObjectIsIgnoredWithoutException() {
        String withoutObjectKey = "{\"id\":\"evt_2\",\"object\":\"event\","
                + "\"type\":\"customer.subscription.deleted\",\"data\":{}}";

        handler().handle(ApiResource.GSON.fromJson(withoutObjectKey, Event.class));
        handler().handle(event("customer.subscription.deleted", "{}"));

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("un corps d'événement vide (ni null ni JSON) est ignoré sans exception")
    void blankRawJsonIsIgnoredWithoutException() {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_9");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getRawJson()).thenReturn("   ");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        handler().handle(event);

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("un data object dont le JSON brut vaut littéralement null est ignoré sans exception")
    void nullRawJsonIsIgnoredWithoutException() {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_10");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getRawJson()).thenReturn(null);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        handler().handle(event);

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("un type d'événement non pris en charge qui atteindrait quand même handle() est ignoré sans exception")
    void unsupportedEventTypeReachingHandleIsIgnoredWithoutException() {
        // Ne devrait jamais arriver en production (supports() filtre en amont dans le
        // dispatcher), mais handle() ne doit pas lever si l'appel se produit quand même.
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("customer.subscription.created", data));

        verifyNoInteractions(subscriptionService);
    }

    // --- Session Checkout ---

    @Test
    @DisplayName("un client_reference_id qui n'est pas un UUID est ignoré sans déclencher de relance")
    void nonUuidClientReferenceIsIgnoredWithoutRetry() {
        String data = "{\"id\":\"cs_3\",\"client_reference_id\":\"pas-un-uuid\","
                + "\"customer\":\"cus_1\",\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("checkout.session.completed", data));

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("une session Checkout sans identifiant client Stripe est ignorée")
    void checkoutWithoutStripeCustomerIsIgnored() {
        String data = "{\"id\":\"cs_4\",\"client_reference_id\":\"" + USER_ID
                + "\",\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("checkout.session.completed", data));

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("une session Checkout sans abonnement Stripe est ignorée")
    void checkoutWithoutStripeSubscriptionIsIgnored() {
        String data = "{\"id\":\"cs_5\",\"client_reference_id\":\"" + USER_ID
                + "\",\"customer\":\"cus_1\"}";

        handler().handle(event("checkout.session.completed", data));

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("une session Checkout avec un identifiant client explicitement `null` en JSON est ignorée")
    void checkoutWithExplicitJsonNullCustomerIsIgnored() {
        String data = "{\"id\":\"cs_5b\",\"client_reference_id\":\"" + USER_ID
                + "\",\"customer\":null,\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("checkout.session.completed", data));

        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("une session Checkout avec un cycle annuel active l'abonnement en YEARLY")
    void checkoutWithYearlyBillingCycleActivatesAsYearly() {
        String data = "{\"id\":\"cs_8\",\"client_reference_id\":\"" + USER_ID
                + "\",\"customer\":\"cus_1\",\"subscription\":\"" + SUBSCRIPTION_ID
                + "\",\"metadata\":{\"billing_cycle\":\"YEARLY\"}}";

        handler().handle(event("checkout.session.completed", data));

        verify(subscriptionService).activateFromStripe(
                eq(USER_ID), eq("cus_1"), eq(SUBSCRIPTION_ID),
                eq(BillingCycle.YEARLY), any(Instant.class));
    }

    @Test
    @DisplayName("une session Checkout sans métadonnée de cycle active quand même l'abonnement, en mensuel")
    void checkoutWithoutBillingCycleMetadataActivatesAsMonthly() {
        String data = "{\"id\":\"cs_6\",\"client_reference_id\":\"" + USER_ID
                + "\",\"customer\":\"cus_1\",\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("checkout.session.completed", data));

        verify(subscriptionService).activateFromStripe(
                eq(USER_ID), eq("cus_1"), eq(SUBSCRIPTION_ID),
                eq(BillingCycle.MONTHLY), any(Instant.class));
    }

    @Test
    @DisplayName("une métadonnée de cycle de facturation inconnue active quand même l'abonnement, en mensuel")
    void checkoutWithUnknownBillingCycleMetadataActivatesAsMonthly() {
        String data = "{\"id\":\"cs_7\",\"client_reference_id\":\"" + USER_ID
                + "\",\"customer\":\"cus_1\",\"subscription\":\"" + SUBSCRIPTION_ID
                + "\",\"metadata\":{\"billing_cycle\":\"WEEKLY\"}}";

        handler().handle(event("checkout.session.completed", data));

        verify(subscriptionService).activateFromStripe(
                eq(USER_ID), eq("cus_1"), eq(SUBSCRIPTION_ID),
                eq(BillingCycle.MONTHLY), any(Instant.class));
    }

    // --- Facture ---

    @Test
    @DisplayName("une facture payée sans échéance renouvelle quand même, avec une échéance de repli")
    void invoicePaidWithoutPeriodEndRenewsWithFallbackDeadline() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"in_4\",\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("invoice.paid", data));

        ArgumentCaptor<Instant> periodEnd = ArgumentCaptor.forClass(Instant.class);
        verify(subscriptionService).renew(eq(sub), periodEnd.capture());
        assertThat(periodEnd.getValue())
                .isAfter(Instant.now().plus(31, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(33, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("un impayé déjà signalé PAST_DUE n'est pas re-signalé")
    void invoiceFailedOnAlreadyPastDueSubscriptionIsNotReReported() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.PAST_DUE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"in_5\",\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("invoice.payment_failed", data));

        // Stripe rejoue ses événements : un second impayé ne doit pas relancer un dunning déjà en cours.
        verify(subscriptionService, never()).markPastDue(any());
    }

    // --- Abonnement ---

    @Test
    @DisplayName("une résiliation programmée déjà à jour n'est pas réécrite")
    void subscriptionUpdateWithUnchangedCancelAtPeriodEndIsNotRewritten() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        sub.setCancelAtPeriodEnd(true);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\",\"cancel_at_period_end\":true}";

        handler().handle(event("customer.subscription.updated", data));

        verify(subscriptionService, never()).markCancelAtPeriodEnd(any(), anyBoolean());
    }

    @Test
    @DisplayName("un événement d'abonnement sans identifiant du tout est ignoré sans exception")
    void subscriptionEventWithoutAnyIdentifierIsIgnoredWithoutException() {
        String data = "{\"cancel_at_period_end\":true}";

        handler().handle(event("customer.subscription.updated", data));

        verifyNoInteractions(repository, subscriptionService);
    }
}
