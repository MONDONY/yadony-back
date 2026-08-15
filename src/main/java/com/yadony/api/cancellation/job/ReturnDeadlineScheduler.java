package com.yadony.api.cancellation.job;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.cancellation.events.ReturnDeadlineExpiredEvent;
import com.yadony.api.cancellation.events.ReturnDeadlineWarningEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job J+3 (D4) : détecte les colis annulés après remise non rendus dans les 3 jours,
 * lève une alerte admin persistée {@code RETURN_DEADLINE_EXPIRED} et publie
 * {@link ReturnDeadlineExpiredEvent}. NE suspend JAMAIS automatiquement : l'admin décide
 * (voir {@code AdminUserController.suspendPublishing}). Idempotent via un marqueur métier
 * persistant sur le bid, indépendant de la résolution de l'alerte admin.
 */
@Component
public class ReturnDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReturnDeadlineScheduler.class);
    static final String ALERT_TYPE = "RETURN_DEADLINE_EXPIRED";

    private final BidRepository bidRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AnnouncementRepository announcementRepository;

    public ReturnDeadlineScheduler(BidRepository bidRepository,
                                   AdminAlertRepository adminAlertRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   AnnouncementRepository announcementRepository) {
        this.bidRepository = bidRepository;
        this.adminAlertRepository = adminAlertRepository;
        this.eventPublisher = eventPublisher;
        this.announcementRepository = announcementRepository;
    }

    @Scheduled(cron = "${yadony.cancellation.return-deadline-cron}", zone = "UTC")
    @Transactional
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        List<BidEntity> warnings = bidRepository
                .findByReturnDeadlineBetweenAndReturnWarningSentAtIsNullAndReturnedAtIsNull(
                        now, now.plusDays(1));
        for (BidEntity bid : warnings) {
            AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                    .orElse(null);
            if (announcement == null) {
                log.warn("Cannot send return warning for bid {}: announcement missing", bid.getId());
                continue;
            }
            eventPublisher.publishEvent(new ReturnDeadlineWarningEvent(
                    bid.getId(), bid.getSenderId(), announcement.getTravelerId(),
                    bid.getReturnDeadline()));
            bid.setReturnWarningSentAt(now);
            bidRepository.save(bid);
        }

        List<BidEntity> expired =
                bidRepository.findByReturnDeadlineBeforeAndReturnedAtIsNullAndReturnExpiredNotifiedAtIsNull(now);
        if (expired.isEmpty()) {
            return;
        }
        for (BidEntity bid : expired) {
            if (bid.getReturnExpiredNotifiedAt() != null) {
                continue;
            }
            String bidIdStr = bid.getId().toString();
            bid.setReturnExpiredNotifiedAt(now);
            bidRepository.save(bid);

            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType(ALERT_TYPE);
            alert.setPayload(String.format(
                    "{\"bidId\":\"%s\",\"senderId\":\"%s\",\"returnDeadline\":\"%s\"}",
                    bid.getId(), bid.getSenderId(), bid.getReturnDeadline()));
            alert.setResolved(false);
            adminAlertRepository.save(alert);

            AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                    .orElse(null);
            eventPublisher.publishEvent(new ReturnDeadlineExpiredEvent(
                    bid.getId(), bid.getSenderId(),
                    announcement == null ? null : announcement.getTravelerId()));
            log.warn("Return deadline expired for bid {} — admin alert raised", bidIdStr);
        }
    }
}
