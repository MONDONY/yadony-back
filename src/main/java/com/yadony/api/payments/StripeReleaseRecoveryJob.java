package com.yadony.api.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Reprise des versements carte réservés dont l'appel Stripe n'a pas été confirmé (processus
 * arrêté entre l'appel et la finalisation, réseau ou Stripe en erreur, PROD_CHECKLIST 9.23).
 *
 * <p>Chaque réservation est traitée seule : bail pris dans une transaction courte, appel Stripe
 * hors transaction (recherche d'un Transfer existant, sinon même clé {@code transfer-<paymentId>}
 * et même montant), finalisation dans une autre transaction courte. Jamais de transaction qui
 * englobe la boucle ni un appel réseau. Un traitement concurrent (livraison en cours, autre
 * instance) ne peut pas prendre le même bail.
 */
@Component
public class StripeReleaseRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(StripeReleaseRecoveryJob.class);

    private final StripeReleaseIntentRepository intentRepository;
    private final StripeReleaseExecutor executor;
    private final boolean enabled;
    private final int batchSize;

    public StripeReleaseRecoveryJob(StripeReleaseIntentRepository intentRepository, StripeReleaseExecutor executor,
                                    @Value("${yadony.payments.release-recovery.enabled:true}") boolean enabled,
                                    @Value("${yadony.payments.release-recovery.batch-size:20}") int batchSize) {
        this.intentRepository = intentRepository;
        this.executor = executor;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${yadony.payments.release-recovery.interval:PT5M}",
            initialDelayString = "${yadony.payments.release-recovery.initial-delay:PT2M}")
    public void scheduledRun() {
        if (enabled) {
            run();
        }
    }

    /** @return le nombre de réservations reprises */
    public int run() {
        List<UUID> due = intentRepository.findDueIds(LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, Math.max(1, batchSize)));
        int resumed = 0;
        for (UUID intentId : due) {
            try {
                StripeReleaseExecutor.Execution execution = executor.resume(intentId);
                if (execution.result() != StripeReleaseExecutor.Result.NOT_OWNED) {
                    resumed++;
                    log.warn("Versement réservé {} repris : {}{}", intentId, execution.result(),
                            execution.error() != null ? " (" + execution.error() + ")" : "");
                }
            } catch (RuntimeException e) {
                log.error("Reprise du versement réservé {} en échec", intentId, e);
            }
        }
        return resumed;
    }
}
