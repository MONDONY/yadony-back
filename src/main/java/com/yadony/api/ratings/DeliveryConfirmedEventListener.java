package com.yadony.api.ratings;

import com.yadony.api.ratings.events.RatingCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class DeliveryConfirmedEventListener {

    private final FraudDetectionService fraudDetectionService;

    public DeliveryConfirmedEventListener(FraudDetectionService fraudDetectionService) {
        this.fraudDetectionService = fraudDetectionService;
    }

    // Story 9.7 — Trigger farming detection after each new rating
    @EventListener
    @Async
    public void onRatingCreated(RatingCreatedEvent event) {
        if (event.getRaterId() != null) {
            fraudDetectionService.detectRatingFarming(event.getRatingId());
        }
    }
}
