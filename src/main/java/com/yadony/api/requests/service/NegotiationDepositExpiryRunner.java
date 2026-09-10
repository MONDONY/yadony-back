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
 * journalisée, le tick suivant repasse. Un dépôt COMPLETED côté pawaPay mais pas encore appliqué
 * n'est jamais expiré : alerte dédupliquée.
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
                if (service.expireMobileMoneyDeposit(threadId)
                        == NegotiationService.DepositExpiryOutcome.DEPOSIT_COMPLETED_NOT_APPLIED) {
                    alerts.raiseOnce(DEPOSIT_COMPLETED_ALERT_PREFIX + threadId,
                            "Dépôt pawaPay COMPLETED mais paiement encore PENDING sur le fil " + threadId,
                            Map.of("threadId", threadId.toString()));
                }
            } catch (Exception e) {
                log.error("Expiration du dépôt mobile money du fil {} échouée : {}", threadId, e.toString());
            }
        }
    }
}
