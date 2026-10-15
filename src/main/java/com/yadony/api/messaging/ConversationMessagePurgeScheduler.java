package com.yadony.api.messaging;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purge horaire des échanges 3 jours après la livraison. Idempotente : une conversation
 * purgée porte {@code messages_purged_at} et sort de la sélection.
 */
@Component
public class ConversationMessagePurgeScheduler {

    private final ConversationMessagePurgeService purgeService;

    public ConversationMessagePurgeScheduler(ConversationMessagePurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @Scheduled(cron = "${yadony.messaging.purge-cron:0 50 * * * *}", zone = "UTC")
    public void purgeDeliveredConversations() {
        purgeService.purgeDue();
    }
}
