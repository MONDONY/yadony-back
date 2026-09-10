package com.yadony.api.requests.service;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chaque minute ({@code yadony.pawapay.deadline-cron}, même cadence que le rail bid) : les fils
 * AWAITING_DEPOSIT dont l'échéance est passée reviennent à « à payer ». Idempotent par
 * {@link NegotiationService#expireMobileMoneyDeposit} (REQUIRES_NEW par fil, claim atomique côté
 * paiement), lot borné, plus anciens d'abord, un échec n'arrête jamais les suivants. Un fil scellé
 * par un règlement concurrent entre la lecture du lot et le commit de
 * {@code expireMobileMoneyDeposit} lève une {@code ObjectOptimisticLockingFailureException} au
 * sortir du proxy REQUIRES_NEW : couverte par le même {@code catch} large que le reste du lot,
 * journalisée, le tick suivant repasse. Un maillon asynchrone perdu (dépôt COMPLETED jamais
 * confirmé, séquestre posé jamais scellé) n'est jamais expiré : il est réparé par
 * {@code expireMobileMoneyDeposit} ; si la réparation lève, l'alerte dédupliquée
 * {@value #DEPOSIT_COMPLETED_ALERT_PREFIX}{@code <threadId>} reste le filet.
 */
@Component
public class NegotiationDepositExpiryRunner {

    static final int BATCH_SIZE = 200;
    static final String DEPOSIT_COMPLETED_ALERT_PREFIX = "NEGO_DEPOSIT_DONE_";
    private static final Logger log = LoggerFactory.getLogger(NegotiationDepositExpiryRunner.class);

    private final NegotiationThreadRepository threadRepo;
    private final NegotiationService service;
    private final AdminAlertEscalator alerts;

    public NegotiationDepositExpiryRunner(NegotiationThreadRepository threadRepo, NegotiationService service,
                                          AdminAlertEscalator alerts) {
        this.threadRepo = threadRepo;
        this.service = service;
        this.alerts = alerts;
    }

    @Scheduled(cron = "${yadony.pawapay.deadline-cron}")
    public void expireUnpaidDeposits() {
        List<UUID> due = threadRepo.findIdsAwaitingDepositExpiredBefore(LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, BATCH_SIZE));
        for (UUID threadId : due) {
            try {
                if (service.expireMobileMoneyDeposit(threadId) == NegotiationService.DepositExpiryOutcome.REPAIRED) {
                    log.info("Fil {} : maillon asynchrone du dépôt mobile money réparé par le balayage", threadId);
                }
            } catch (Exception e) {
                // Une expiration ordinaire ne lève pas ; une exception ici vient d'une réparation
                // (scellement ou confirmation rejoués) qui a échoué à son tour, ou d'un état
                // incohérent : un humain doit regarder, une seule fois par fil.
                log.error("Expiration ou réparation du dépôt mobile money du fil {} échouée : {}", threadId, e.toString());
                alerts.raiseOnce(DEPOSIT_COMPLETED_ALERT_PREFIX + threadId,
                        "Réparation du dépôt mobile money échouée sur le fil " + threadId + " : " + e,
                        Map.of("threadId", threadId.toString()));
            }
        }
    }
}
