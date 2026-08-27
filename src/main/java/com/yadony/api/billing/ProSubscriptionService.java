package com.yadony.api.billing;

import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Machine à états de l'abonnement PRO.
 *
 * <p>Toute transition passe par ce service : il met à jour la ligne, aligne
 * l'accès via {@link ProAccessSynchronizer} et journalise les transitions
 * fermantes dans {@code audit_log}.
 */
@Service
public class ProSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(ProSubscriptionService.class);

    private static final String AUDIT_ENTITY_TYPE = "BILLING";

    private final ProSubscriptionRepository repository;
    private final ProAccessSynchronizer accessSynchronizer;
    private final AuditService auditService;

    public ProSubscriptionService(ProSubscriptionRepository repository,
                                  ProAccessSynchronizer accessSynchronizer,
                                  AuditService auditService) {
        this.repository = repository;
        this.accessSynchronizer = accessSynchronizer;
        this.auditService = auditService;
    }

    /**
     * Ouvre une grâce pour un compte PRO gratuit historique.
     * Utilisé par le backfill de la migration V231 et par
     * {@link LegacyProGraceListener} pour les upgrades gratuits résiduels.
     *
     * <p>Réutilise la ligne existante si l'utilisateur en a déjà une :
     * l'index {@code uq_pro_subscriptions_user} n'autorise qu'un abonnement
     * vivant par utilisateur, statut fermé compris. Un utilisateur dont
     * l'abonnement est EXPIRED conserve donc sa ligne, qui est recyclée.
     */
    @Transactional
    public ProSubscriptionEntity openLegacyGrace(UUID userId, int graceDays) {
        ProSubscriptionEntity sub = repository.findByUserId(userId)
                .orElseGet(ProSubscriptionEntity::new);
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.LEGACY_GRACE);
        sub.setSource(ProSubscriptionSource.LEGACY_FREE);
        sub.setGraceExpiresAt(Instant.now().plus(graceDays, ChronoUnit.DAYS));
        // Nettoyage des résidus d'un cycle précédent : sans cela, un
        // past_due_since périmé ferait expirer la grâce dès le premier passage
        // du cron de dunning.
        sub.setPastDueSince(null);
        sub.setCancelAtPeriodEnd(false);
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(userId, true);
        log.info("Legacy PRO grace opened for user {} until {}", userId, saved.getGraceExpiresAt());
        return saved;
    }

    /**
     * Entrée en impayé. L'accès reste ouvert : Stripe relance la carte
     * pendant plusieurs jours et couper immédiatement pénaliserait un
     * incident bancaire passager.
     */
    @Transactional
    public ProSubscriptionEntity markPastDue(ProSubscriptionEntity sub) {
        sub.setStatus(ProSubscriptionStatus.PAST_DUE);
        sub.setPastDueSince(Instant.now());
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(sub.getUserId(), true);
        log.info("Subscription {} marked PAST_DUE", sub.getId());
        return saved;
    }

    /** Paiement finalement encaissé : retour à ACTIVE. */
    @Transactional
    public ProSubscriptionEntity clearPastDue(ProSubscriptionEntity sub) {
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setPastDueSince(null);
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(sub.getUserId(), true);
        log.info("Subscription {} recovered to ACTIVE", sub.getId());
        return saved;
    }

    /** Droit non converti arrivé à échéance : grâce écoulée ou dunning épuisé. */
    @Transactional
    public ProSubscriptionEntity expire(ProSubscriptionEntity sub) {
        return close(sub, ProSubscriptionStatus.EXPIRED, "BILLING_SUBSCRIPTION_EXPIRED");
    }

    /** Résiliation d'un abonnement, ou révocation d'un octroi administrateur. */
    @Transactional
    public ProSubscriptionEntity cancel(ProSubscriptionEntity sub) {
        return close(sub, ProSubscriptionStatus.CANCELED, "BILLING_SUBSCRIPTION_CANCELED");
    }

    private ProSubscriptionEntity close(ProSubscriptionEntity sub,
                                        ProSubscriptionStatus target,
                                        String auditAction) {
        String previousStatus = sub.getStatus().name();
        sub.setStatus(target);
        sub.setPastDueSince(null);
        ProSubscriptionEntity saved = repository.save(sub);

        accessSynchronizer.sync(sub.getUserId(), false);
        auditService.log(AUDIT_ENTITY_TYPE, sub.getId(), auditAction, sub.getUserId(),
                Map.of("previousStatus", previousStatus, "source", sub.getSource().name()));

        log.info("Subscription {} closed: {} -> {}", sub.getId(), previousStatus, target);
        return saved;
    }
}
