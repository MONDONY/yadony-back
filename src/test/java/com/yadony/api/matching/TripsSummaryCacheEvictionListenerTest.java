package com.yadony.api.matching;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.events.AnnouncementDeletedEvent;
import com.yadony.api.matching.events.TripActivityChangedEvent;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TripsSummaryCacheEvictionListener")
class TripsSummaryCacheEvictionListenerTest {

    @Mock
    private TripsSummaryService tripsSummaryService;

    @Test
    @DisplayName("livraison confirmée → invalide le résumé du voyageur")
    void deliveryConfirmed_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), UUID.randomUUID(), traveler));

        verify(tripsSummaryService).evictSummary(traveler);
    }

    @Test
    @DisplayName("paiement libéré → invalide le résumé du voyageur")
    void paymentReleased_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onPaymentReleased(new PaymentReleasedEvent(
                UUID.randomUUID(), traveler, UUID.randomUUID(), BigDecimal.TEN));

        verify(tripsSummaryService).evictSummary(traveler);
    }

    @Test
    @DisplayName("trajet publié → invalide le résumé, sinon activeTrips reste "
            + "figé et la pastille « Pour mes trajets » demeure grisée")
    void announcementPublished_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                UUID.randomUUID(), traveler, "Aboubakar D.", "Paris", "Abidjan"));

        verify(tripsSummaryService).evictSummary(traveler);
    }

    @Test
    @DisplayName("trajet supprimé → invalide le résumé du voyageur")
    void announcementDeleted_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onAnnouncementDeleted(
                new AnnouncementDeletedEvent(UUID.randomUUID(), traveler));

        verify(tripsSummaryService).evictSummary(traveler);
    }

    @Test
    @DisplayName("FLUTTER-FC — trajet annulé → invalide le résumé du voyageur")
    void tripCancelled_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onTripCancelled(new TripCancelledEvent(
                UUID.randomUUID(), traveler, List.of(), "TRAVELER_CANCEL", List.of()));

        verify(tripsSummaryService).evictSummary(traveler);
    }

    @Test
    @DisplayName("FLUTTER-FC — trajet qui entre ou sort des actifs → invalide le résumé")
    void tripActivityChanged_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onTripActivityChanged(new TripActivityChangedEvent(UUID.randomUUID(), traveler));

        verify(tripsSummaryService).evictSummary(traveler);
    }
}
