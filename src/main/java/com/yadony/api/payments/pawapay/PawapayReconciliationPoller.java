package com.yadony.api.payments.pawapay;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
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
 */
@Component
public class PawapayReconciliationPoller {

    static final Duration MIN_AGE = Duration.ofSeconds(60);
    /** Une opération ACCEPTED est interrogeable tout de suite : au-delà, CREATED + NOT_FOUND = jamais partie. */
    static final Duration CREATED_TIMEOUT = Duration.ofMinutes(2);
    /** Au-delà, une opération non-CREATED toujours inconnue de pawaPay est escaladée (revue ronde 1, point 4). */
    static final Duration UNKNOWN_ESCALATION_AGE = Duration.ofHours(1);
    /**
     * Borne un passage (revue ronde 1, point 3) : sans elle, un incident prolongé chez
     * pawaPay pourrait accumuler des centaines d'opérations {@code OPEN} et faire durer une
     * exécution des heures durant, sur l'unique pool de scheduling partagé par tous les
     * crons du dépôt.
     */
    static final int BATCH_SIZE = 200;
    static final String UNKNOWN_ALERT_TYPE_PREFIX = "PAWAPAY_UNKNOWN_OP_";

    private static final Logger log = LoggerFactory.getLogger(PawapayReconciliationPoller.class);

    private final PawapayOperationRepository repository;
    private final PawapayClient client;
    private final PawapayOperationService operations;
    private final AdminAlertService alerts;
    private final AdminAlertRepository alertRepository;
    private final PawapayProperties props;

    public PawapayReconciliationPoller(PawapayOperationRepository repository, PawapayClient client,
                                       PawapayOperationService operations, AdminAlertService alerts,
                                       AdminAlertRepository alertRepository, PawapayProperties props) {
        this.repository = repository;
        this.client = client;
        this.operations = operations;
        this.alerts = alerts;
        this.alertRepository = alertRepository;
        this.props = props;
    }

    @Scheduled(cron = "${yadony.pawapay.poll-cron}")
    public void reconcile() {
        if (!props.enabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<PawapayOperationEntity> open = repository.findByStatusInAndUpdatedAtBefore(
                PawapayOperationStatus.OPEN, now.minus(MIN_AGE),
                PageRequest.of(0, BATCH_SIZE, Sort.by("updatedAt").ascending()));
        for (PawapayOperationEntity op : open) {
            try {
                reconcileOne(op, now);
            } catch (RestClientException e) {
                // Anticipé : panne réseau ou pawaPay indisponible. Le message suffit, la
                // pile n'apporterait rien pour un échec de cette nature — et ne jamais
                // laisser cette opération geler la réconciliation des suivantes.
                log.error("pawaPay poller : {} {} non réconciliée, pawaPay indisponible ({})",
                        op.getKind(), op.getId(), e.getMessage());
            } catch (Exception e) {
                // Inattendu : un bug ici mérite sa pile, sinon un défaut déterministe ne
                // produirait qu'une ligne ERROR sans trace toutes les deux minutes.
                log.error("pawaPay poller : {} {} non réconciliée, erreur inattendue", op.getKind(), op.getId(), e);
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
            if (op.getCreatedAt().plus(UNKNOWN_ESCALATION_AGE).isBefore(now)) {
                escalateUnknown(op);
            }
        }
    }

    /**
     * Un versement réellement perdu chez pawaPay ne serait sinon jamais payé, sans que
     * personne ne le sache : un simple {@code log.warn} ne rafraîchit pas {@code updatedAt}
     * (la ligne resterait sélectionnée pour toujours) et ne crée aucun événement Sentry (le
     * niveau minimum de la stack est ERROR). Dédupliqué par un type d'alerte portant
     * l'identifiant de l'opération — jamais par le contenu du payload JSON, que PostgreSQL
     * reformate à la relecture (jsonb) et qui ne matcherait alors plus jamais (revue ronde 1,
     * points 2 et 4).
     */
    private void escalateUnknown(PawapayOperationEntity op) {
        String type = UNKNOWN_ALERT_TYPE_PREFIX + op.getId();
        if (!alertRepository.findByTypeAndResolved(type, false).isEmpty()) {
            return;
        }
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType(type);
        alert.setPayload("{\"operationId\":\"" + op.getId() + "\",\"kind\":\"" + op.getKind() + "\"}");
        alert.setResolved(false);
        alertRepository.save(alert);
        alerts.raise(type, "Opération pawaPay " + op.getKind() + " " + op.getId()
                        + " introuvable chez pawaPay depuis plus d'une heure",
                Map.of("operationId", op.getId().toString(), "kind", op.getKind().name()));
    }
}
