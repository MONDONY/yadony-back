package com.yadony.api.matching;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.events.AnnouncementDeletedEvent;
import com.yadony.api.matching.events.TripActivityChangedEvent;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Invalide le cache {@code trips-summary} d'un voyageur dès qu'un de ses
 * indicateurs mensuels change :
 * <ul>
 *   <li>{@link DeliveryConfirmedEvent} — un colis livré fait passer un bid en
 *       {@code COMPLETED} → « kg vendus ce mois » évolue ;</li>
 *   <li>{@link PaymentReleasedEvent} — l'escrow libéré → « revenus » évolue.</li>
 * </ul>
 *
 * <p>Sans cette invalidation, le résumé (mis en cache par
 * {@link TripsSummaryService#computeSummary}) resterait figé jusqu'au TTL
 * Caffeine (5 min) — c'est la cause du « le kg vendu / le revenu du mois ne se
 * met pas à jour ».
 *
 * <p>Communication inter-packages par Spring Events (règle d'archi : pas
 * d'injection directe de service cross-package). {@code AFTER_COMMIT} garantit
 * que l'écriture (bid COMPLETED / paiement RELEASED) est committée avant
 * l'éviction, donc le prochain recalcul lit l'état à jour.
 */
@Component
public class TripsSummaryCacheEvictionListener {

    private final TripsSummaryService tripsSummaryService;

    public TripsSummaryCacheEvictionListener(TripsSummaryService tripsSummaryService) {
        this.tripsSummaryService = tripsSummaryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        tripsSummaryService.evictSummary(event.getTravelerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentReleased(PaymentReleasedEvent event) {
        tripsSummaryService.evictSummary(event.getTravelerId());
    }

    /**
     * Un trajet qui devient visible fait monter {@code activeTrips}, l'indicateur
     * qui pilote côté application le garde-fou du filtre « Pour mes trajets ».
     * Sans cette éviction, le voyageur qui publie son premier trajet retrouve la
     * pastille grisée et le message « Aucun trajet actif » pendant tout le TTL du
     * cache, soit cinq minutes, alors qu'il vient précisément de faire ce que le
     * message lui demandait.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        tripsSummaryService.evictSummary(event.travelerId());
    }

    /** Symétrique : un trajet supprimé fait baisser {@code activeTrips}. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementDeleted(AnnouncementDeletedEvent event) {
        tripsSummaryService.evictSummary(event.travelerId());
    }

    /**
     * Un trajet annulé quitte {@code activeTrips} (FLUTTER-FC) : sans cette éviction,
     * « Trajets actifs » gardait le trajet annulé pendant tout le TTL du cache.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripCancelled(TripCancelledEvent event) {
        tripsSummaryService.evictSummary(event.getTravelerId());
    }

    /**
     * Dépublication, retrait / restauration par la modération, clôture automatique :
     * autant de passages qui font varier {@code activeTrips} sans création, suppression
     * ni annulation.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripActivityChanged(TripActivityChangedEvent event) {
        tripsSummaryService.evictSummary(event.travelerId());
    }
}
