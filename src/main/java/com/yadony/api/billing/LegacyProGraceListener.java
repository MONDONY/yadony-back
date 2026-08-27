package com.yadony.api.billing;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Garantit qu'aucun compte PRO n'existe sans ligne dans {@code pro_subscriptions}.
 *
 * <p>Tant que le lot 2 n'est pas déployé, {@code POST /auth/me/upgrade-to-pro}
 * accorde encore le statut PRO gratuitement. Sans ce listener, un tel compte
 * n'aurait aucun abonnement, échapperait aux tâches planifiées et resterait
 * PRO gratuit indéfiniment.
 *
 * <p>Aucun risque de boucle avec {@link ProAccessSynchronizer} : quand
 * celui-ci publie l'événement, la ligne d'abonnement existe déjà et le
 * listener ne fait rien.
 */
@Component
public class LegacyProGraceListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyProGraceListener.class);

    private final ProSubscriptionRepository repository;
    private final ProSubscriptionService subscriptionService;
    private final BillingProperties properties;

    public LegacyProGraceListener(ProSubscriptionRepository repository,
                                  ProSubscriptionService subscriptionService,
                                  BillingProperties properties) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.properties = properties;
    }

    @EventListener
    @Transactional
    public void onUserProStatusChanged(UserProStatusChangedEvent event) {
        if (!event.isPro()) {
            return;
        }
        // Tester la présence de la ligne ne suffit pas : elle est recyclée et
        // survit à un EXPIRED. Un utilisateur dont la grâce s'est éteinte et qui
        // repasserait par l'upgrade gratuit garderait sinon isProAccount = true
        // avec un abonnement fermé, hors de portée des tâches planifiées.
        boolean alreadyCovered = repository.findByUserId(event.userId())
                .map(sub -> sub.getStatus().grantsProAccess())
                .orElse(false);
        if (alreadyCovered) {
            return;
        }
        subscriptionService.openLegacyGrace(event.userId(), properties.legacyGraceDaysOrDefault());
        log.info("Orphan PRO user {} given a legacy grace period", event.userId());
    }
}
