package com.yadony.api.billing;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Garantit qu'aucun compte PRO n'existe sans ligne dans {@code pro_subscriptions},
 * et qu'aucune ligne n'y reste ouverte pour un compte qui n'est plus PRO.
 *
 * <p>Filet défensif, pas un chemin d'activation attendu : depuis ce lot,
 * {@code POST /auth/me/upgrade-to-pro} ne fait plus qu'éditer le profil PRO
 * (raison sociale, SIRET) et n'accorde plus jamais {@code isProAccount = true}
 * gratuitement — seul {@link ProAccessSynchronizer}, piloté par Stripe via
 * {@link ProSubscriptionService}, publie encore un
 * {@code UserProStatusChangedEvent} à {@code isPro() == true}. La branche
 * montée en PRO de ce listener protège malgré tout contre un compte qui
 * basculerait {@code isProAccount = true} par un autre chemin que celui-là —
 * migration de données, régression future, octroi admin direct — sans ligne
 * {@code pro_subscriptions} associée.
 *
 * <p>Symétriquement, {@code DELETE /auth/me/upgrade-to-pro} redescend le
 * drapeau : sans la branche downgrade (voir {@link #onDowngrade}), la ligne
 * d'abonnement resterait ouverte alors que l'utilisateur n'est plus PRO.
 *
 * <p>Aucun risque de boucle avec {@link ProAccessSynchronizer} : quand
 * celui-ci publie l'événement, la ligne d'abonnement est déjà dans l'état
 * cible et ce listener ne fait rien.
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
            onDowngrade(event.userId());
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

    /**
     * {@code DELETE /auth/me/upgrade-to-pro} met {@code is_pro_account = false}
     * puis publie cet événement, sans qu'aucun autre composant de
     * {@code billing/} n'y réagisse jusqu'ici : la ligne {@code pro_subscriptions}
     * restait {@code LEGACY_GRACE} ou {@code ACTIVE}, donc {@code grantsProAccess()}
     * continuait de valoir vrai alors que le drapeau était faux.
     *
     * <p>Le garde {@code grantsProAccess()} rend aussi cet appel idempotent :
     * un abonnement déjà fermé (EXPIRED/CANCELED) n'est jamais repassé à
     * {@link ProSubscriptionService#cancel}, qui réécrirait sinon une entrée
     * {@code audit_log} avec un {@code previousStatus} égal au statut cible.
     *
     * <p>Aucun risque de boucle : {@code UserService.downgradePro} pose le
     * drapeau à faux *avant* de publier l'événement, donc le
     * {@link ProAccessSynchronizer#sync} déclenché par {@code cancel} constate
     * que le drapeau est déjà dans l'état voulu et ne republie rien.
     */
    private void onDowngrade(UUID userId) {
        repository.findByUserId(userId)
                .filter(sub -> sub.getStatus().grantsProAccess())
                .ifPresent(sub -> {
                    subscriptionService.cancel(sub);
                    log.info("Subscription {} closed following downgrade of user {}", sub.getId(), userId);
                });
    }
}
