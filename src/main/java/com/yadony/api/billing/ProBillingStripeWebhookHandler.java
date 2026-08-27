package com.yadony.api.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.yadony.api.common.stripe.StripeWebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Traduit les événements Stripe Billing en transitions de la machine à états
 * de l'abonnement PRO.
 *
 * <p>Découvert automatiquement par {@code StripeEventDispatcher}, qui injecte
 * la liste des {@link StripeWebhookHandler}. Aucun des types traités ici n'est
 * revendiqué par les handlers existants : le dispatcher s'arrête au premier
 * handler acceptant un type, un chevauchement en masquerait un.
 *
 * <p><b>Aucune méthode ne lève d'exception sur une donnée absente.</b>
 * {@code StripeEventProcessor} traite toute exception comme un échec à
 * réessayer : sept relances, puis mise en {@code DEAD_LETTER} et alerte
 * administrateur. Un événement inexploitable est journalisé et ignoré.
 */
@Component
public class ProBillingStripeWebhookHandler implements StripeWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(ProBillingStripeWebhookHandler.class);

    private static final Set<String> SUPPORTED = Set.of(
            "checkout.session.completed",
            "invoice.paid",
            "invoice.payment_failed",
            "customer.subscription.updated",
            "customer.subscription.deleted"
    );

    private final ProSubscriptionRepository repository;
    private final ProSubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    public ProBillingStripeWebhookHandler(ProSubscriptionRepository repository,
                                          ProSubscriptionService subscriptionService,
                                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void handle(Event event) {
        JsonNode data = readDataObject(event);
        if (data == null) {
            return;
        }
        switch (event.getType()) {
            case "checkout.session.completed" -> onCheckoutCompleted(data);
            case "invoice.paid" -> onInvoicePaid(data);
            case "invoice.payment_failed" -> onInvoiceFailed(data);
            case "customer.subscription.updated" -> onSubscriptionUpdated(data);
            case "customer.subscription.deleted" -> onSubscriptionDeleted(data);
            default -> log.warn("Unexpected billing event type {}", event.getType());
        }
    }

    /**
     * Lit le JSON brut plutôt que l'objet désérialisé : {@code getObject()}
     * renvoie un Optional vide dès que la version d'API du compte Stripe
     * diffère de celle du SDK — piège déjà rencontré dans
     * {@code PaymentStripeWebhookHandler}.
     */
    private JsonNode readDataObject(Event event) {
        try {
            String rawJson = event.getDataObjectDeserializer().getRawJson();
            if (rawJson == null || rawJson.isBlank()) {
                log.warn("Billing event {} has no data object", event.getId());
                return null;
            }
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            log.warn("Cannot parse billing event {}: {}", event.getId(), e.getMessage());
            return null;
        }
    }

    private void onCheckoutCompleted(JsonNode data) {
        String reference = text(data, "client_reference_id");
        String customerId = text(data, "customer");
        String subscriptionId = text(data, "subscription");

        if (reference == null || customerId == null || subscriptionId == null) {
            log.warn("Checkout session incomplete (reference={}, customer={}, subscription={})",
                    reference, customerId, subscriptionId);
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            log.warn("Checkout session carries a non-UUID client_reference_id: {}", reference);
            return;
        }

        BillingCycle cycle = readCycle(data);
        // L'échéance exacte arrivera par invoice.paid ; on pose une borne
        // provisoire pour que la ligne ne paraisse jamais expirée entre-temps.
        Instant provisionalEnd = Instant.now().plus(
                cycle == BillingCycle.YEARLY ? 366 : 32, ChronoUnit.DAYS);

        subscriptionService.activateFromStripe(userId, customerId, subscriptionId,
                cycle, provisionalEnd);
    }

    private BillingCycle readCycle(JsonNode data) {
        String raw = data.path("metadata").path("billing_cycle").asText(null);
        if (raw == null) {
            log.warn("Checkout session without billing_cycle metadata — defaulting to MONTHLY");
            return BillingCycle.MONTHLY;
        }
        try {
            return BillingCycle.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown billing_cycle metadata '{}' — defaulting to MONTHLY", raw);
            return BillingCycle.MONTHLY;
        }
    }

    private void onInvoicePaid(JsonNode data) {
        find(text(data, "subscription")).ifPresent(sub -> {
            long periodEnd = data.path("period_end").asLong(0L);
            Instant end = periodEnd > 0
                    ? Instant.ofEpochSecond(periodEnd)
                    : Instant.now().plus(32, ChronoUnit.DAYS);
            subscriptionService.renew(sub, end);
        });
    }

    private void onInvoiceFailed(JsonNode data) {
        find(text(data, "subscription")).ifPresent(sub -> {
            if (sub.getStatus() == ProSubscriptionStatus.PAST_DUE) {
                return;
            }
            subscriptionService.markPastDue(sub);
        });
    }

    private void onSubscriptionUpdated(JsonNode data) {
        find(text(data, "id")).ifPresent(sub -> {
            boolean cancelAtPeriodEnd = data.path("cancel_at_period_end").asBoolean(false);
            if (sub.isCancelAtPeriodEnd() == cancelAtPeriodEnd) {
                return;
            }
            subscriptionService.markCancelAtPeriodEnd(sub, cancelAtPeriodEnd);
        });
    }

    private void onSubscriptionDeleted(JsonNode data) {
        find(text(data, "id")).ifPresent(sub -> {
            // Stripe rejoue ses événements : sans cette garde, une seconde
            // réception réécrirait une entrée audit_log avec un statut
            // précédent égal au statut cible.
            if (!sub.getStatus().grantsProAccess()) {
                return;
            }
            subscriptionService.cancel(sub);
        });
    }

    private Optional<ProSubscriptionEntity> find(String stripeSubscriptionId) {
        if (stripeSubscriptionId == null) {
            log.warn("Billing event without a subscription identifier");
            return Optional.empty();
        }
        Optional<ProSubscriptionEntity> found =
                repository.findByStripeSubscriptionId(stripeSubscriptionId);
        if (found.isEmpty()) {
            log.warn("No PRO subscription matches Stripe subscription {}", stripeSubscriptionId);
        }
        return found;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
