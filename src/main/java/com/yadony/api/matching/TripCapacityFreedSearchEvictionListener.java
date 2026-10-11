package com.yadony.api.matching;

import com.yadony.api.matching.events.TripCapacityFreedEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Un trajet complet redevient réservable : la recherche en cache le montrerait encore
 * « Complet » jusqu'au TTL, alors que l'abonné vient d'être prévenu qu'il a de la place
 * (FLUTTER-JH).
 */
@Component
public class TripCapacityFreedSearchEvictionListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @CacheEvict(value = "announcements-search", allEntries = true)
    public void onTripCapacityFreed(TripCapacityFreedEvent event) {
        // L'éviction est portée par l'annotation.
    }
}
