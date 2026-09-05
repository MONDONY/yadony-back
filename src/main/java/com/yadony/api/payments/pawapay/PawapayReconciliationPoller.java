package com.yadony.api.payments.pawapay;

import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rattrapage des callbacks perdus et des initiations sans réponse. Idempotent : toute
 * transition passe par {@code PawapayOperationService.apply}, qui ignore un état final.
 *
 * <p>C'est le filet de sécurité de tout le rail pawaPay : un callback qui se perd (réseau,
 * redémarrage, signature refusée à tort) laisserait sinon une opération bloquée pour
 * toujours dans un état non final. Une opération en erreur (panne réseau, 5xx pawaPay) ne
 * doit jamais interrompre le lot : chacune est traitée dans son propre bloc try/catch.
 */
@Component
public class PawapayReconciliationPoller {

    static final Duration MIN_AGE = Duration.ofSeconds(60);
    /** Une opération ACCEPTED est interrogeable tout de suite : au-delà, CREATED + NOT_FOUND = jamais partie. */
    static final Duration CREATED_TIMEOUT = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(PawapayReconciliationPoller.class);

    private final PawapayOperationRepository repository;
    private final PawapayClient client;
    private final PawapayOperationService operations;
    private final PawapayProperties props;

    public PawapayReconciliationPoller(PawapayOperationRepository repository, PawapayClient client,
                                       PawapayOperationService operations, PawapayProperties props) {
        this.repository = repository;
        this.client = client;
        this.operations = operations;
        this.props = props;
    }

    @Scheduled(cron = "${yadony.pawapay.poll-cron}")
    public void reconcile() {
        if (!props.enabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<PawapayOperationEntity> open = repository.findByStatusInAndUpdatedAtBefore(
                PawapayOperationStatus.OPEN, now.minus(MIN_AGE));
        for (PawapayOperationEntity op : open) {
            try {
                reconcileOne(op, now);
            } catch (Exception e) {
                // Ne jamais laisser une opération en erreur geler la réconciliation des
                // suivantes : chaque itération est indépendante, l'exception s'arrête ici.
                log.error("pawaPay poller : {} {} non réconciliée ({})", op.getKind(), op.getId(), e.toString());
            }
        }
    }

    private void reconcileOne(PawapayOperationEntity op, LocalDateTime now) {
        Optional<PawapayOperationSnapshot> snapshot = client.getStatus(op.getKind(), op.getId());
        if (snapshot.isPresent()) {
            PawapayOperationSnapshot s = snapshot.get();
            operations.apply(op.getId(), s.status(), s.failureCode(), s.failureMessage(), s.providerTransactionId(),
                    s.authorizationUrl(), s.raw(), PawapayOperationService.Source.POLL);
            return;
        }
        boolean neverLeft = op.getStatus() == PawapayOperationStatus.CREATED
                && op.getCreatedAt().plus(CREATED_TIMEOUT).isBefore(now);
        if (neverLeft) {
            operations.apply(op.getId(), PawapayOperationStatus.SUBMIT_REJECTED, "SUBMIT_TIMEOUT",
                    "Initiation sans réponse et inconnue de pawaPay", null, null, null,
                    PawapayOperationService.Source.SYSTEM);
        } else if (op.getStatus() != PawapayOperationStatus.CREATED) {
            log.warn("pawaPay poller : {} {} acceptée mais inconnue côté pawaPay, à surveiller", op.getKind(), op.getId());
        }
    }
}
