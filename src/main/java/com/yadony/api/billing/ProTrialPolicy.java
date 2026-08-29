package com.yadony.api.billing;

import com.yadony.api.auth.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Décide si un utilisateur a droit à l'essai gratuit, et pour combien de jours.
 *
 * <p>Une seule classe porte la règle, et les deux appelants la partagent : le Checkout
 * ({@link StripeBillingService}) et l'état d'abonnement lu par le portail
 * ({@code GET /billing/subscription}). Dupliquer la condition les laisserait diverger —
 * le portail annoncerait « 7 jours offerts » à quelqu'un que Stripe facturerait
 * immédiatement, ce qui est bien pire que de ne rien annoncer du tout.
 */
@Component
public class ProTrialPolicy {

    /**
     * Nombre de trajets réalisés exigé pour prétendre à l'essai.
     *
     * <p>{@code users.total_trips} est incrémenté une seule fois par trajet physique, à la
     * première livraison confirmée sur une annonce et après commit
     * ({@code TravelerStatsListener}). Plusieurs colis sur un même trajet ne comptent donc
     * que pour un, et un trajet annulé n'a jamais été compté.
     */
    private static final int REQUIRED_COMPLETED_TRIPS = 1;

    private final UserRepository userRepository;
    private final ProSubscriptionRepository subscriptionRepository;
    private final BillingProperties properties;

    public ProTrialPolicy(UserRepository userRepository,
                          ProSubscriptionRepository subscriptionRepository,
                          BillingProperties properties) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.properties = properties;
    }

    /**
     * Durée de l'essai auquel cet utilisateur a droit, ou {@code null} s'il n'y a pas droit.
     *
     * <p>Trois conditions cumulatives :
     * <ol>
     *   <li>un essai est configuré ({@code yadony.billing.trial-days} strictement positif) ;</li>
     *   <li>l'utilisateur a réalisé au moins un trajet — l'essai récompense un usage réel de
     *       l'application, il n'est pas une porte d'entrée ;</li>
     *   <li>il n'a jamais été client Stripe. L'identifiant client survit à une résiliation
     *       ({@code purgeTransverseFields} le conserve volontairement) : sans cette
     *       condition, résilier puis se réabonner rendrait l'essai renouvelable sans fin.</li>
     * </ol>
     *
     * <p>Ne pas y avoir droit n'empêche jamais de s'abonner : le Checkout part simplement
     * sans essai, et l'utilisateur est PRO dès le premier paiement.
     */
    public Long trialDaysFor(UUID userId) {
        Long configured = properties.trialDaysOrNull();
        if (configured == null) {
            return null;
        }
        if (!hasCompletedTrip(userId) || hasBeenStripeCustomer(userId)) {
            return null;
        }
        return configured;
    }

    /** Vrai si cet utilisateur verra un essai s'il ouvre une session Checkout maintenant. */
    public boolean isEligible(UUID userId) {
        return trialDaysFor(userId) != null;
    }

    private boolean hasCompletedTrip(UUID userId) {
        return userRepository.findById(userId)
                .map(user -> user.getTotalTrips() >= REQUIRED_COMPLETED_TRIPS)
                .orElse(false);
    }

    private boolean hasBeenStripeCustomer(UUID userId) {
        return subscriptionRepository.findByUserId(userId)
                .map(ProSubscriptionEntity::getStripeCustomerId)
                .filter(id -> !id.isBlank())
                .isPresent();
    }
}
