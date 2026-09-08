package com.yadony.api.payments.pawapay;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.dto.PawapayOpenOperation;
import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Rattrapage des callbacks perdus et des initiations sans réponse. Idempotent : toute
 * transition passe par {@code PawapayOperationService.apply}, qui ignore un état final.
 *
 * <p>C'est le filet de sécurité de tout le rail pawaPay : un callback qui se perd (réseau,
 * redémarrage, signature refusée à tort) laisserait sinon une opération bloquée pour
 * toujours dans un état non final. Une opération en erreur (panne réseau, 5xx pawaPay) ne
 * doit jamais interrompre le lot : chacune est traitée dans son propre bloc try/catch.
 *
 * <p>Ne dépend PAS de {@code yadony.pawapay.enabled}, comme {@code MobileMoneyPaymentDeadlineScheduler} :
 * un interrupteur d'urgence doit arrêter les NOUVEAUX mouvements d'argent (les initiations
 * restent gardées par {@code props.enabled()}), jamais la réconciliation de ceux déjà en vol —
 * couper le rail pendant un incident ne doit pas aussi arrêter le filet qui rattrape un dépôt
 * bloqué, ni l'escalade ({@link #escalateUnknown}) qui vit précisément ici.
 */
@Component
public class PawapayReconciliationPoller {

    static final Duration MIN_AGE = Duration.ofSeconds(60);
    /** Une opération ACCEPTED est interrogeable tout de suite : au-delà, CREATED + NOT_FOUND = jamais partie. */
    static final Duration CREATED_TIMEOUT = Duration.ofMinutes(2);
    /** Au-delà, une opération non-CREATED toujours inconnue de pawaPay est escaladée. */
    static final Duration UNKNOWN_ESCALATION_AGE = Duration.ofHours(1);
    /**
     * Borne un passage : sans elle, un incident prolongé chez pawaPay pourrait accumuler des
     * centaines d'opérations {@code OPEN} et faire durer une exécution des heures durant, sur
     * l'unique pool de scheduling partagé par tous les crons du dépôt.
     */
    static final int BATCH_SIZE = 200;
    static final String UNKNOWN_ALERT_TYPE_PREFIX = "PAWAPAY_UNKNOWN_OP_";

    private static final Logger log = LoggerFactory.getLogger(PawapayReconciliationPoller.class);

    private final PawapayOperationRepository repository;
    private final PawapayClient client;
    private final PawapayOperationService operations;
    private final AdminAlertEscalator alerts;

    public PawapayReconciliationPoller(PawapayOperationRepository repository, PawapayClient client,
                                       PawapayOperationService operations, AdminAlertEscalator alerts) {
        this.repository = repository;
        this.client = client;
        this.operations = operations;
        this.alerts = alerts;
    }

    @Scheduled(cron = "${yadony.pawapay.poll-cron}")
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<PawapayOpenOperation> open = repository.findOpenForReconciliation(
                PawapayOperationStatus.OPEN, now.minus(MIN_AGE),
                PageRequest.of(0, BATCH_SIZE, Sort.by("updatedAt").ascending()));
        for (PawapayOpenOperation op : open) {
            try {
                reconcileOne(op, now);
            } catch (RestClientException e) {
                // Anticipé : panne réseau ou pawaPay indisponible. Le message suffit, la
                // pile n'apporterait rien pour un échec de cette nature — et ne jamais
                // laisser cette opération geler la réconciliation des suivantes.
                log.error("pawaPay poller : {} {} non réconciliée, pawaPay indisponible ({})",
                        op.kind(), op.id(), e.getMessage());
            } catch (Exception e) {
                // Inattendu : un bug ici mérite sa pile, sinon un défaut déterministe ne
                // produirait qu'une ligne ERROR sans trace toutes les deux minutes.
                log.error("pawaPay poller : {} {} non réconciliée, erreur inattendue", op.kind(), op.id(), e);
            }
        }
    }

    private void reconcileOne(PawapayOpenOperation op, LocalDateTime now) {
        Optional<PawapayOperationSnapshot> snapshot = client.getStatus(op.kind(), op.id());
        if (snapshot.isPresent()) {
            PawapayOperationSnapshot s = snapshot.get();
            operations.apply(op.id(), s.status(), s.failureCode(), s.failureMessage(), s.providerTransactionId(),
                    s.authorizationUrl(), s.raw(), PawapayOperationService.Source.POLL);
            return;
        }
        boolean neverLeft = op.status() == PawapayOperationStatus.CREATED
                && op.createdAt().plus(CREATED_TIMEOUT).isBefore(now);
        if (neverLeft) {
            operations.apply(op.id(), PawapayOperationStatus.SUBMIT_REJECTED, "SUBMIT_TIMEOUT",
                    "Initiation sans réponse et inconnue de pawaPay", null, null, null,
                    PawapayOperationService.Source.SYSTEM);
        } else if (op.status() != PawapayOperationStatus.CREATED) {
            log.warn("pawaPay poller : {} {} acceptée mais inconnue côté pawaPay, à surveiller", op.kind(), op.id());
            if (op.createdAt().plus(UNKNOWN_ESCALATION_AGE).isBefore(now)) {
                escalateUnknown(op);
            }
        }
    }

    /**
     * Un versement réellement perdu chez pawaPay ne serait sinon jamais payé, sans que
     * personne ne le sache : un simple {@code log.warn} ne rafraîchit pas {@code updatedAt}
     * (la ligne resterait sélectionnée pour toujours) et ne crée aucun événement Sentry (le
     * niveau minimum de la stack est ERROR). Dédupliqué par un type d'alerte portant
     * l'identifiant de l'opération ({@link AdminAlertEscalator}).
     */
    private void escalateUnknown(PawapayOpenOperation op) {
        alerts.raiseOnce(UNKNOWN_ALERT_TYPE_PREFIX + op.id(),
                "Opération pawaPay " + op.kind() + " " + op.id() + " introuvable chez pawaPay depuis plus d'une heure",
                Map.of("operationId", op.id().toString(), "kind", op.kind().name()));
    }
}
