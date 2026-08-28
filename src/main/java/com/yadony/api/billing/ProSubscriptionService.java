package com.yadony.api.billing;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
        // Purge d'abord les champs transverses (cf. purgeTransverseFields) : la ligne
        // recyclée passe en source LEGACY_FREE et ne doit porter aucun résidu d'un
        // précédent cycle Stripe ou octroi admin — un past_due_since périmé ferait
        // expirer la grâce dès le premier passage du cron de dunning, et un
        // stripe_subscription_id périmé serait ramené par findByStripeSubscriptionId
        // au premier webhook reçu pour cet identifiant, qui ne correspond plus à cet
        // abonnement. Le client Stripe, lui, est conservé : il est réutilisé en cas de
        // réabonnement après annulation, pour éviter de fragmenter l'historique de
        // facturation.
        purgeTransverseFields(sub);
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.LEGACY_GRACE);
        sub.setSource(ProSubscriptionSource.LEGACY_FREE);
        sub.setGraceExpiresAt(Instant.now().plus(graceDays, ChronoUnit.DAYS));
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(userId, true);
        log.info("Legacy PRO grace opened for user {} until {}", userId, saved.getGraceExpiresAt());
        return saved;
    }

    /**
     * Souscription payante confirmée par Stripe.
     *
     * <p>Recycle la ligne existante comme {@link #openLegacyGrace} : l'index
     * {@code uq_pro_subscriptions_user} n'autorise qu'un abonnement vivant par
     * utilisateur, statut fermé compris.
     *
     * <p>Purge les champs qui n'ont plus de sens une fois l'abonnement payé :
     * la grâce historique, un impayé antérieur, une résiliation programmée
     * sur un cycle précédent, et les traces d'un octroi administrateur
     * antérieur (la source change, donc les traces de l'origine précédente
     * ne doivent pas survivre).
     */
    @Transactional
    public ProSubscriptionEntity activateFromStripe(UUID userId,
                                                    String customerId,
                                                    String subscriptionId,
                                                    BillingCycle cycle,
                                                    Instant periodEnd) {
        ProSubscriptionEntity sub = repository.findByUserId(userId)
                .orElseGet(ProSubscriptionEntity::new);
        purgeTransverseFields(sub);
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.STRIPE);
        sub.setStripeCustomerId(customerId);
        sub.setStripeSubscriptionId(subscriptionId);
        sub.setBillingCycle(cycle);
        sub.setCurrentPeriodEnd(periodEnd);
        ProSubscriptionEntity saved = repository.save(sub);

        accessSynchronizer.sync(userId, true);
        auditService.log(AUDIT_ENTITY_TYPE, saved.getId(), "BILLING_SUBSCRIPTION_ACTIVATED", userId,
                Map.of("cycle", cycle.name(), "stripeSubscriptionId", subscriptionId));

        log.info("Subscription {} activated from Stripe for user {} ({})",
                saved.getId(), userId, cycle);
        return saved;
    }

    /**
     * Accès PRO offert par un administrateur : partenariat, geste commercial.
     *
     * <p>Sans échéance — il court jusqu'à révocation explicite par un administrateur,
     * ou renoncement de l'utilisateur lui-même. Aucune tâche planifiée ne le ferme :
     * les trois requêtes de {@code ProSubscriptionScheduler} filtrent sur des statuts
     * ou des dates que cet octroi ne porte pas.
     *
     * <p>Recycle la ligne existante comme les autres créateurs : l'index
     * {@code uq_pro_subscriptions_user} n'autorise qu'un abonnement vivant par
     * utilisateur, statuts fermés compris.
     *
     * <p>Le motif n'entre pas dans {@code audit_log} : cette table est immuable, et un
     * texte libre saisi par un administrateur y graverait définitivement d'éventuelles
     * données personnelles. Il vit dans {@code admin_grant_reason}.
     *
     * <p>Refuse l'octroi quand un abonnement Stripe encore vivant couvre déjà
     * l'utilisateur (statut qui {@link ProSubscriptionStatus#grantsProAccess()
     * accorde encore l'accès}) : recycler la ligne purgerait
     * {@code stripeSubscriptionId} pendant que Stripe continue de prélever, et
     * {@link StripeBillingService#createPortalSession} exige ce champ — l'utilisateur
     * ne pourrait alors même plus atteindre le Customer Portal pour résilier. C'est
     * l'image miroir du garde-fou posé par {@link com.yadony.api.auth.UserService#downgradePro}.
     */
    @Transactional
    public ProSubscriptionEntity grantByAdmin(UUID userId, UUID adminId, String reason) {
        ProSubscriptionEntity sub = repository.findByUserId(userId)
                .orElseGet(ProSubscriptionEntity::new);

        if (sub.isStripeManaged()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "active-stripe-subscription", "Active Stripe Subscription",
                    "Cet utilisateur a un abonnement PRO payant en cours — orientez-le vers "
                            + "le Customer Portal Stripe pour le résilier avant d'offrir un accès.");
        }

        // La source change : les traces d'un cycle Stripe précédent ne doivent pas
        // survivre (cf. purgeTransverseFields). Un stripe_subscription_id résiduel
        // serait retrouvé par findByStripeSubscriptionId au prochain webhook, qui
        // piloterait alors cette ligne depuis un abonnement qui n'est plus le sien.
        // Le client Stripe, lui, est conservé : il est réutilisé en cas de
        // réabonnement après annulation.
        purgeTransverseFields(sub);
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.ADMIN_GRANT);
        sub.setGrantedByAdminId(adminId);
        sub.setAdminGrantReason(reason);
        sub.setGrantedAt(Instant.now());

        // Enregistrer AVANT de synchroniser : LegacyProGraceListener réagit à
        // l'événement et ouvrirait une LEGACY_GRACE si aucun abonnement ne couvrait
        // encore l'utilisateur — écrasant cet octroi.
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(userId, true);

        auditService.log(AUDIT_ENTITY_TYPE, saved.getId(), "BILLING_ADMIN_GRANTED", adminId,
                Map.of("targetUserId", userId.toString()));

        log.info("PRO access granted to user {} by admin {}", userId, adminId);
        return saved;
    }

    /**
     * Révoque un accès PRO offert par un administrateur.
     *
     * <p>Le garde-fou « ce n'est pas un octroi administrateur » descend ici depuis
     * {@code AdminUserController} : tout futur appelant en bénéficie. Refuse aussi une
     * double révocation — sans cela, une entrée {@code previousStatus=CANCELED ->
     * CANCELED} serait gravée dans {@code audit_log}, immuable.
     *
     * <p>Journalise {@code BILLING_ADMIN_GRANT_REVOKED} avec l'administrateur comme
     * acteur, sur le modèle exact de {@link #grantByAdmin} : {@link #close} journalise
     * l'utilisateur comme acteur, ce qui convient aux appelants système (cron, webhook,
     * renoncement de l'utilisateur) mais produirait ici une entrée indiscernable d'un
     * renoncement volontaire — sans trace de l'administrateur révoquant.
     */
    @Transactional
    public ProSubscriptionEntity revokeAdminGrant(UUID userId, UUID adminId) {
        ProSubscriptionEntity sub = repository.findByUserId(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "no-subscription", "Not Found", "Aucun abonnement PRO sur ce compte"));

        if (sub.getSource() != ProSubscriptionSource.ADMIN_GRANT) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "not-an-admin-grant", "Not An Admin Grant",
                    "Cet abonnement n'est pas un accès offert : il se résilie depuis Stripe.");
        }

        if (!sub.getStatus().grantsProAccess()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "already-revoked", "Already Revoked",
                    "Cet accès offert a déjà été révoqué.");
        }

        sub.setStatus(ProSubscriptionStatus.CANCELED);
        sub.setPastDueSince(null);
        ProSubscriptionEntity saved = repository.save(sub);

        accessSynchronizer.sync(userId, false);
        auditService.log(AUDIT_ENTITY_TYPE, saved.getId(), "BILLING_ADMIN_GRANT_REVOKED", adminId,
                Map.of("targetUserId", userId.toString()));

        log.info("PRO access revoked from user {} by admin {}", userId, adminId);
        return saved;
    }

    /**
     * Résiliation programmée, ou son annulation, depuis le Customer Portal.
     * L'accès n'est pas coupé : la période en cours est déjà réglée. C'est
     * {@code ProSubscriptionScheduler.closeEndedCancellations} ou le webhook
     * {@code customer.subscription.deleted} qui fermera à l'échéance.
     */
    @Transactional
    public ProSubscriptionEntity markCancelAtPeriodEnd(ProSubscriptionEntity sub,
                                                       boolean cancelAtPeriodEnd) {
        sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        ProSubscriptionEntity saved = repository.save(sub);
        log.info("Subscription {} cancelAtPeriodEnd set to {}", sub.getId(), cancelAtPeriodEnd);
        return saved;
    }

    /**
     * Échéance encaissée : repousse la période et sort d'un éventuel impayé.
     *
     * <p>Refuse de ressusciter un abonnement {@code CANCELED} : une résiliation
     * volontaire ne doit jamais être rouverte par un encaissement tardif (facture
     * en retard sur un abonnement déjà résilié, webhook rejoué après coup...).
     * {@code ACTIVE}, {@code PAST_DUE} et {@code EXPIRED} restent éligibles —
     * ressusciter un {@code EXPIRED} après un encaissement tardif de dunning est
     * au contraire le comportement souhaité.
     *
     * <p>Journalise {@code BILLING_SUBSCRIPTION_REACTIVATED} uniquement quand la
     * résurrection a réellement lieu, c'est-à-dire quand le statut de départ ne
     * donnait pas déjà l'accès ({@code EXPIRED}) : {@link #close} avait écrit une
     * entrée fermante à ce moment-là, sans celle-ci la piste d'audit laisserait
     * croire que le compte est resté fermé.
     */
    @Transactional
    public ProSubscriptionEntity renew(ProSubscriptionEntity sub, Instant periodEnd) {
        if (sub.getStatus() == ProSubscriptionStatus.CANCELED) {
            log.warn("Refusing to renew CANCELED subscription {} — a voluntary cancellation "
                    + "is never reopened by a late payment", sub.getId());
            return sub;
        }

        ProSubscriptionStatus previousStatus = sub.getStatus();
        boolean isReactivation = !previousStatus.grantsProAccess();

        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setPastDueSince(null);
        sub.setCurrentPeriodEnd(periodEnd);
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(sub.getUserId(), true);

        if (isReactivation) {
            auditService.log(AUDIT_ENTITY_TYPE, saved.getId(), "BILLING_SUBSCRIPTION_REACTIVATED",
                    sub.getUserId(), Map.of("previousStatus", previousStatus.name()));
        }

        log.info("Subscription {} renewed until {}", sub.getId(), periodEnd);
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

    /**
     * Purge les champs transverses avant qu'un créateur ({@link #openLegacyGrace},
     * {@link #activateFromStripe}, {@link #grantByAdmin}) ne recycle la ligne existante
     * d'un utilisateur pour une nouvelle source : {@code stripeSubscriptionId},
     * {@code billingCycle}, {@code currentPeriodEnd}, {@code graceExpiresAt},
     * {@code pastDueSince}, {@code cancelAtPeriodEnd}, {@code grantedByAdminId},
     * {@code adminGrantReason} et {@code grantedAt}. Chaque créateur pose ensuite ses
     * champs propres, qui écrasent le cas échéant la valeur purgée.
     *
     * <p>{@code stripeCustomerId} en est volontairement absent : il est réutilisé en cas
     * de réabonnement après annulation, quelle que soit la nouvelle source.
     */
    private void purgeTransverseFields(ProSubscriptionEntity sub) {
        sub.setStripeSubscriptionId(null);
        sub.setBillingCycle(null);
        sub.setCurrentPeriodEnd(null);
        sub.setGraceExpiresAt(null);
        sub.setPastDueSince(null);
        sub.setCancelAtPeriodEnd(false);
        sub.setGrantedByAdminId(null);
        sub.setAdminGrantReason(null);
        sub.setGrantedAt(null);
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
