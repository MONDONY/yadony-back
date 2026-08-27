package com.yadony.api.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.common.stripe.StripeWebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
    private final AdminAlertService adminAlertService;

    public ProBillingStripeWebhookHandler(ProSubscriptionRepository repository,
                                          ProSubscriptionService subscriptionService,
                                          ObjectMapper objectMapper,
                                          AdminAlertService adminAlertService) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.adminAlertService = adminAlertService;
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

    /**
     * Statuts {@code payment_status} d'une Checkout Session pour lesquels l'argent
     * est effectivement acquis (ou n'a jamais été dû).
     */
    private static final Set<String> PAYMENT_CONFIRMED_STATUSES = Set.of("paid", "no_payment_required");

    private void onCheckoutCompleted(JsonNode data) {
        String sessionId = text(data, "id");
        String paymentStatus = text(data, "payment_status");
        // "checkout.session.completed" peut arriver avec payment_status=unpaid : le
        // prélèvement SEPA (très utilisé sur ce marché) confirme la session avant que
        // le débit soit encaissé. Un champ absent est traité comme "pas confirmé" —
        // on ne peut pas prouver que l'argent est là, donc on n'accorde pas l'accès ;
        // au pire on active un peu plus tard via invoice.paid, jamais trop tôt.
        if (paymentStatus == null || !PAYMENT_CONFIRMED_STATUSES.contains(paymentStatus)) {
            log.info("Checkout session {} not confirmed as paid yet (payment_status={}) — "
                    + "waiting for the matching invoice.paid", sessionId, paymentStatus);
            return;
        }

        String customerId = text(data, "customer");
        String subscriptionId = text(data, "subscription");
        if (customerId == null || subscriptionId == null) {
            log.warn("Checkout session incomplete (customer={}, subscription={})",
                    customerId, subscriptionId);
            return;
        }

        UUID userId = resolveUserId(data, subscriptionId);
        if (userId == null) {
            log.error("Checkout session {} is paid but no user could be resolved "
                    + "(client_reference_id absent/invalid and subscription metadata unusable)", sessionId);
            adminAlertService.raise("BILLING_CHECKOUT_UNRESOLVED_USER",
                    "Session Checkout " + sessionId + " payée, mais aucun utilisateur n'a pu être "
                            + "rattaché à l'abonnement Stripe " + subscriptionId,
                    Map.of("checkoutSessionId", String.valueOf(sessionId),
                            "customerId", customerId,
                            "subscriptionId", subscriptionId));
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

    /**
     * Résout l'utilisateur d'une session Checkout : {@code client_reference_id}
     * d'abord, avec repli sur la métadonnée {@code user_id} de l'abonnement Stripe.
     *
     * <p>Cette métadonnée est posée par {@code StripeBillingService} via
     * {@code subscription_data.putMetadata("user_id", …)} à la création de la
     * session : elle atterrit sur l'objet {@code Subscription} une fois créé, pas
     * sur la Session elle-même (le paramètre {@code subscription_data} n'est pas
     * ré-exposé tel quel dans le payload de la Session). Il faut donc aller la
     * chercher via l'API Stripe.
     */
    private UUID resolveUserId(JsonNode data, String subscriptionId) {
        String reference = text(data, "client_reference_id");
        if (reference != null) {
            try {
                return UUID.fromString(reference);
            } catch (IllegalArgumentException e) {
                log.warn("Checkout session carries a non-UUID client_reference_id: {} — "
                        + "trying the subscription's user_id metadata as a fallback", reference);
            }
        }
        return resolveUserIdFromSubscriptionMetadata(subscriptionId);
    }

    private UUID resolveUserIdFromSubscriptionMetadata(String subscriptionId) {
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId);
            String userIdMetadata = subscription.getMetadata() != null
                    ? subscription.getMetadata().get("user_id")
                    : null;
            if (userIdMetadata == null) {
                return null;
            }
            return UUID.fromString(userIdMetadata);
        } catch (StripeException e) {
            log.warn("Cannot retrieve Stripe subscription {} to resolve its user_id metadata: {}",
                    subscriptionId, e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            log.warn("Stripe subscription {} carries a non-UUID user_id metadata", subscriptionId);
            return null;
        }
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
        find(invoiceSubscriptionId(data)).ifPresent(sub -> {
            long periodEnd = invoiceServicePeriodEnd(data);
            Instant end = periodEnd > 0
                    ? Instant.ofEpochSecond(periodEnd)
                    : Instant.now().plus(32, ChronoUnit.DAYS);
            subscriptionService.renew(sub, end);
        });
    }

    /**
     * Échéance de la nouvelle période de service, à ne pas confondre avec le
     * {@code period_end} racine de la facture : celui-ci ferme la période
     * d'USAGE déjà facturée (proche de l'instant présent à chaque renouvellement),
     * pas la nouvelle période de service dont l'accès dépend. La source non
     * ambiguë est {@code lines.data[0].period.end}. Sans ce correctif,
     * {@code currentPeriodEnd} retombait quasiment à maintenant à chaque
     * renouvellement, et {@code closeEndedCancellations} aurait fermé un
     * abonnement {@code cancelAtPeriodEnd=true} dès son passage suivant — alors
     * que la période venait justement d'être payée.
     */
    private static long invoiceServicePeriodEnd(JsonNode invoice) {
        JsonNode lines = invoice.path("lines").path("data");
        if (lines.isArray() && !lines.isEmpty()) {
            long lineItemEnd = lines.get(0).path("period").path("end").asLong(0L);
            if (lineItemEnd > 0) {
                return lineItemEnd;
            }
        }
        return invoice.path("period_end").asLong(0L);
    }

    private void onInvoiceFailed(JsonNode data) {
        find(invoiceSubscriptionId(data)).ifPresent(sub -> {
            if (sub.getStatus() == ProSubscriptionStatus.PAST_DUE) {
                return;
            }
            subscriptionService.markPastDue(sub);
        });
    }

    /**
     * Lit l'identifiant d'abonnement d'une {@code Invoice}.
     *
     * <p>Le champ racine {@code subscription} a été retiré de l'objet Invoice en
     * {@code 2025-03-31.basil} ; sa forme actuelle est
     * {@code parent.subscription_details.subscription}. On lit d'abord cette forme
     * actuelle, avec repli sur l'ancien champ racine : la version d'API Stripe
     * effectivement utilisée dépend de la configuration du compte/endpoint webhook,
     * pas de celle du SDK embarqué ici, et une migration future pourrait la faire
     * évoluer encore — ce repli évite de dépendre de l'une ou l'autre.
     */
    private static String invoiceSubscriptionId(JsonNode invoice) {
        JsonNode parent = invoice.get("parent");
        if (parent != null && !parent.isNull()) {
            JsonNode subscriptionDetails = parent.get("subscription_details");
            if (subscriptionDetails != null && !subscriptionDetails.isNull()) {
                String nested = text(subscriptionDetails, "subscription");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return text(invoice, "subscription");
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
