package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Échéance de contestation d'une déclaration « expéditeur absent » (HANDOVER) : sans réponse de
 * l'expéditeur, l'absence est confirmée ({@link CancellationConfirmedEvent} : colis annulé,
 * séquestre remboursé à l'expéditeur, compteur d'incidents). Logique d'annulation : vit dans
 * {@code cancellation/} (elle était sous {@code payments/cash/job}).
 *
 * <p>Chaque déclaration est traitée sous le verrou du colis, comme le scan de remise qui peut la
 * lever au même instant ({@link SenderNoShowHandoverGate}) : son statut est relu en base après le
 * verrou, et seule une déclaration encore {@code PENDING_CONFIRMATION} est confirmée. Sans ce verrou,
 * le job confirmait une déclaration que le scan venait de lever et repassait le colis remis en
 * CANCELLED (remboursement d'un colis qui voyage).
 *
 * <p>Colis déjà remis ({@link BidStatus#SENDER_SHOWED_UP}, déclaration ouverte avant la porte) :
 * la déclaration est close en RESOLVED sans événement ({@link SenderNoShowClosing}).
 */
@Component
public class NoShowContestationTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(NoShowContestationTimeoutJob.class);

    private final CancellationRepository cancellationRepo;
    private final ApplicationEventPublisher events;
    private final BidRepository bidRepository;
    private final SenderNoShowClosing closing;

    public NoShowContestationTimeoutJob(CancellationRepository cancellationRepo,
                                        ApplicationEventPublisher events,
                                        BidRepository bidRepository,
                                        AuditService auditService) {
        this.cancellationRepo = cancellationRepo;
        this.events = events;
        this.bidRepository = bidRepository;
        this.closing = new SenderNoShowClosing(cancellationRepo, auditService);
    }

    @Scheduled(cron = "${yadony.cash-commission.no-show-timeout-cron}", zone = "UTC")
    @Transactional
    public void run() {
        cancellationRepo.findExpiredPending(OffsetDateTime.now()).forEach(this::confirm);
    }

    private void confirm(CancellationEntity c) {
        bidRepository.lockForUpdate(c.getBidId());
        CancellationStatus current = cancellationRepo.findNoShowStatusById(c.getId()).orElse(null);
        if (current != CancellationStatus.PENDING_CONFIRMATION) {
            log.info("Échéance de contestation : déclaration {} déjà {} (scan ou réponse concurrente), ignorée",
                    c.getId(), current);
            return;
        }
        Optional<BidEntity> bid = bidRepository.findById(c.getBidId());
        if (bid.isPresent() && BidStatus.SENDER_SHOWED_UP.contains(bid.get().getStatus())) {
            closing.closeBecauseParcelHandedOver(c, bid.get().getStatus(), null);
            return;
        }
        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepo.save(c);
        events.publishEvent(new CancellationConfirmedEvent(
                c.getBidId(), c.getId(), CancellationReason.valueOf(c.getReason())));
    }
}
