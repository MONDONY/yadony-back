package com.yadony.api.billing;

import com.stripe.exception.StripeException;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Création des sessions Stripe Checkout et Customer Portal.
 *
 * <p>La clé d'API est posée globalement par {@code StripeConfig.init()}, comme
 * ailleurs dans le projet : les appels statiques du SDK l'utilisent.
 */
@Service
public class StripeBillingService {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingService.class);

    private final ProSubscriptionRepository repository;
    private final BillingProperties properties;

    public StripeBillingService(ProSubscriptionRepository repository,
                                BillingProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Ouvre une session Checkout pour l'utilisateur.
     *
     * <p>L'identifiant utilisateur voyage dans {@code client_reference_id} :
     * c'est lui que {@link ProBillingStripeWebhookHandler} lira pour rattacher
     * l'abonnement au bon compte.
     */
    public String createCheckoutSession(UUID userId, BillingCycle cycle) {
        if (!properties.stripePricesConfigured()) {
            throw new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "billing-not-configured", "Billing Unavailable",
                    "Le service de billing de l'abonnement PRO n'est pas encore disponible.");
        }

        repository.findByUserId(userId).ifPresent(sub -> {
            if (sub.getStatus() == ProSubscriptionStatus.ACTIVE
                    || sub.getStatus() == ProSubscriptionStatus.PAST_DUE) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "subscription-already-active", "Already Subscribed",
                        "Vous avez déjà un abonnement PRO en cours.");
            }
        });

        String existingCustomerId = repository.findByUserId(userId)
                .map(ProSubscriptionEntity::getStripeCustomerId)
                .orElse(null);

        try {
            com.stripe.param.checkout.SessionCreateParams.Builder params =
                    com.stripe.param.checkout.SessionCreateParams.builder()
                            .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                            .setSuccessUrl(properties.successUrl())
                            .setCancelUrl(properties.cancelUrl())
                            .setClientReferenceId(userId.toString())
                            .putMetadata("billing_cycle", cycle.name())
                            .setSubscriptionData(
                                    com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder()
                                            .putMetadata("billing_cycle", cycle.name())
                                            .putMetadata("user_id", userId.toString())
                                            .build())
                            .addLineItem(
                                    com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                                            .setPrice(properties.priceFor(cycle))
                                            .setQuantity(1L)
                                            .build());

            // Réutiliser le client Stripe existant évite d'en créer un second
            // au réabonnement, et conserve l'historique de facturation.
            if (existingCustomerId != null && !existingCustomerId.isBlank()) {
                params.setCustomer(existingCustomerId);
            }

            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(params.build());
            log.info("Checkout session {} created for user {} ({})",
                    session.getId(), userId, cycle);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe refused the checkout session for user {}: {}", userId, e.getMessage());
            throw new IllegalStateException("Stripe checkout session creation failed", e);
        }
    }

    /** Ouvre une session Customer Portal pour gérer carte et résiliation. */
    public String createPortalSession(UUID userId) {
        String customerId = repository.findByUserId(userId)
                .map(ProSubscriptionEntity::getStripeCustomerId)
                .filter(id -> !id.isBlank())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "no-stripe-customer", "No Billing Account",
                        "Aucun abonnement payant n'est rattaché à ce compte."));

        try {
            com.stripe.param.billingportal.SessionCreateParams params =
                    com.stripe.param.billingportal.SessionCreateParams.builder()
                            .setCustomer(customerId)
                            .setReturnUrl(properties.portalReturnUrl())
                            .build();

            com.stripe.model.billingportal.Session session =
                    com.stripe.model.billingportal.Session.create(params);
            log.info("Portal session created for user {}", userId);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe refused the portal session for user {}: {}", userId, e.getMessage());
            throw new IllegalStateException("Stripe portal session creation failed", e);
        }
    }
}
