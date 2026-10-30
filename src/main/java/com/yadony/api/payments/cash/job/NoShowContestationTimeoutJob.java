package com.yadony.api.payments.cash.job;

import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.cancellation.SenderNoShowHandoverGate;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Échéance de contestation d'une déclaration « expéditeur absent » (HANDOVER) : sans réponse de
 * l'expéditeur, l'absence est confirmée ({@link CancellationConfirmedEvent} : colis annulé,
 * séquestre remboursé à l'expéditeur, compteur d'incidents).
 *
 * <p>Sauf si le colis a déjà été remis ({@link SenderNoShowHandoverGate#parcelAlreadyHandedOver}) :
 * déclaration ouverte avant que la porte de remise ne bloque le scan. L'expéditeur est venu, le
 * colis voyage : la déclaration est close en RESOLVED (comme une déclaration rejetée), sans
 * événement, donc sans remboursement ni pénalité, et tracée dans {@code audit_log}.
 */
@Component
public class NoShowContestationTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(NoShowContestationTimeoutJob.class);

    private final CancellationRepository cancellationRepo;
    private final ApplicationEventPublisher events;
    private final BidRepository bidRepository;
    private final AuditService auditService;

    public NoShowContestationTimeoutJob(CancellationRepository cancellationRepo,
                                        ApplicationEventPublisher events,
                                        BidRepository bidRepository,
                                        AuditService auditService) {
        this.cancellationRepo = cancellationRepo;
        this.events = events;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
    }

    @Scheduled(cron = "${yadony.cash-commission.no-show-timeout-cron}", zone = "UTC")
    @Transactional
    public void run() {
        cancellationRepo.findExpiredPending(OffsetDateTime.now()).forEach(this::confirm);
    }

    private void confirm(CancellationEntity c) {
        Optional<BidEntity> bid = bidRepository.findById(c.getBidId());
        if (bid.isPresent() && SenderNoShowHandoverGate.parcelAlreadyHandedOver(bid.get().getStatus())) {
            closeBecauseParcelHandedOver(c, bid.get());
            return;
        }
        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepo.save(c);
        events.publishEvent(new CancellationConfirmedEvent(
                c.getBidId(), c.getId(), CancellationReason.valueOf(c.getReason())));
    }

    private void closeBecauseParcelHandedOver(CancellationEntity c, BidEntity bid) {
        log.warn("Échéance de contestation : absence de l'expéditeur non confirmée, le colis {} est déjà {}"
                + " (déclaration {} close, aucun remboursement)", bid.getId(), bid.getStatus(), c.getId());
        c.setNoShowStatus(CancellationStatus.RESOLVED);
        cancellationRepo.save(c);
        auditService.log("CANCELLATION", c.getId(), SenderNoShowHandoverGate.AUDIT_CLOSED_PARCEL_HANDED_OVER, null,
                Map.of("bidId", c.getBidId().toString(),
                        "bidStatus", bid.getStatus().name(),
                        "previousStatus", CancellationStatus.PENDING_CONFIRMATION.name()));
    }
}
