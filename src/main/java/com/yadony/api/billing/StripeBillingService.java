package com.yadony.api.billing;

import com.stripe.exception.StripeException;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
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
    private final ProTrialPolicy trialPolicy;

    public StripeBillingService(ProSubscriptionRepository repository,
                                BillingProperties properties,
                                ProTrialPolicy trialPolicy) {
        this.repository = repository;
        this.properties = properties;
        this.trialPolicy = trialPolicy;
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

        Optional<ProSubscriptionEntity> existingSubscription = repository.findByUserId(userId);

        existingSubscription.ifPresent(sub -> {
            if (!sub.getStatus().allowsNewCheckout()) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "subscription-already-active", "Already Subscribed",
                        "Vous avez déjà un abonnement PRO en cours.");
            }
        });

        String existingCustomerId = existingSubscription
                .map(ProSubscriptionEntity::getStripeCustomerId)
                .orElse(null);

        // La règle entière vit dans ProTrialPolicy, partagée avec GET /billing/subscription :
        // c'est ce qui garantit que l'essai annoncé au portail est exactement celui que
        // Stripe accordera.
        Long trialDays = trialPolicy.trialDaysFor(userId);

        try {
            com.stripe.param.checkout.SessionCreateParams.Builder params =
                    com.stripe.param.checkout.SessionCreateParams.builder()
                            .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                            .setSuccessUrl(properties.successUrl())
                            .setCancelUrl(properties.cancelUrl())
                            .setClientReferenceId(userId.toString())
                            .putMetadata("billing_cycle", cycle.name())
                            .setSubscriptionData(subscriptionData(userId, cycle, trialDays))
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

    /**
     * Métadonnées de l'abonnement, et l'essai gratuit quand il s'applique.
     *
     * <p>{@code user_id} est le repli de {@code ProBillingStripeWebhookHandler} quand
     * {@code client_reference_id} manque : il doit rester posé, essai ou pas.
     */
    private com.stripe.param.checkout.SessionCreateParams.SubscriptionData subscriptionData(
            UUID userId, BillingCycle cycle, Long trialDays) {
        com.stripe.param.checkout.SessionCreateParams.SubscriptionData.Builder builder =
                com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("billing_cycle", cycle.name())
                        .putMetadata("user_id", userId.toString());
        if (trialDays != null) {
            builder.setTrialPeriodDays(trialDays);
        }
        return builder.build();
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
