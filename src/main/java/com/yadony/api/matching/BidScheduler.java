package com.yadony.api.matching;

import com.yadony.api.matching.events.HandoverAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class BidScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BidScheduler.class);

    private final BidRepository bidRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BidScheduler(BidRepository bidRepository, ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.eventPublisher = eventPublisher;
    }

    // Runs every 15 minutes
    @Scheduled(fixedRate = 15 * 60 * 1000)
    @Transactional
    public void scanAndSendH2Alerts() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime threshold = now.plusHours(2);
        
        logger.debug("Scanning for bids needing H-2 alerts (from now={} up to threshold={})", now, threshold);
        
        List<BidEntity> pendingAlerts = bidRepository.findBidsNeedingH2Alert(now, threshold);
        
        for (BidEntity bid : pendingAlerts) {
            try {
                logger.info("Sending H-2 Alert for Bid ID: {}", bid.getId());

                bid.setH2AlertSentAt(now);
                bidRepository.save(bid);

                eventPublisher.publishEvent(new HandoverAlertEvent(
                        bid.getId(),
                        bid.getSenderId(),
                        bid.getHandoverLocation(),
                        bid.getHandoverDeadline()
                ));
                
            } catch (Exception e) {
                logger.error("Failed to process H-2 alert for bid " + bid.getId(), e);
            }
        }
    }
}
