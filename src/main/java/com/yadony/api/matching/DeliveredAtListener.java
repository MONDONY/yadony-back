package com.yadony.api.matching;

import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Date de livraison d'un bid (V285), lue par la fenêtre d'appel audio. */
@Component
public class DeliveredAtListener {

    private final BidRepository bidRepository;

    public DeliveredAtListener(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        bidRepository.findById(event.getBidId()).ifPresent(bid -> {
            bid.markDelivered(LocalDateTime.now(ZoneOffset.UTC));
            bidRepository.save(bid);
        });
    }
}
