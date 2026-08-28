package com.yadony.api.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Downgrades différés de l'abonnement PRO.
 *
 * <p>Les trois tâches sont sous le même interrupteur
 * {@code yadony.billing.scheduler-enabled}, faux par défaut : sans le parcours
 * de paiement du lot 2, elles expireraient des comptes qui n'ont aucun moyen
 * de s'abonner.
 *
 * <p>{@code @EnableScheduling} est déjà porté par {@code YadonyBackApplication}.
 */
@Component
public class ProSubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProSubscriptionScheduler.class);

    private final ProSubscriptionRepository repository;
    private final ProSubscriptionService subscriptionService;
    private final BillingProperties properties;

    public ProSubscriptionScheduler(ProSubscriptionRepository repository,
                                    ProSubscriptionService subscriptionService,
                                    BillingProperties properties) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.properties = properties;
    }

    /** Grâces historiques arrivées à échéance. */
    @Scheduled(cron = "${yadony.billing.expiry-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void expireLegacyGrace() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        List<ProSubscriptionEntity> expired = repository.findByStatusAndGraceExpiresAtBefore(
                ProSubscriptionStatus.LEGACY_GRACE, Instant.now());
        expired.forEach(subscriptionService::expire);
        if (!expired.isEmpty()) {
            log.info("Legacy PRO grace expired for {} subscriptions", expired.size());
        }
    }

    /** Impayés dont les relances Stripe n'ont rien donné. */
    @Scheduled(cron = "${yadony.billing.expiry-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void expireExhaustedDunning() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        Instant threshold = Instant.now()
                .minus(properties.dunningGraceDaysOrDefault(), ChronoUnit.DAYS);
        List<ProSubscriptionEntity> exhausted = repository.findByStatusAndPastDueSinceBefore(
                ProSubscriptionStatus.PAST_DUE, threshold);
        exhausted.forEach(subscriptionService::expire);
        if (!exhausted.isEmpty()) {
            log.info("Dunning exhausted for {} subscriptions", exhausted.size());
        }
    }

    /**
     * Filet de sécurité : ferme les résiliations dont la période payée est
     * écoulée si le webhook {@code customer.subscription.deleted} a été manqué.
     */
    @Scheduled(cron = "${yadony.billing.expiry-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void closeEndedCancellations() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        List<ProSubscriptionEntity> ended = repository
                .findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(
                        ProSubscriptionStatus.ACTIVE, Instant.now());
        ended.forEach(subscriptionService::cancel);
        if (!ended.isEmpty()) {
            log.info("Closed {} subscriptions whose paid period ended", ended.size());
        }
    }
}
