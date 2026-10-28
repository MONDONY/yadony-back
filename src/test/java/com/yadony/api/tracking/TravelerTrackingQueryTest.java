package com.yadony.api.tracking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TripArrivalInstant;
import com.yadony.api.tracking.dto.TravelerTrackingView;
import com.yadony.api.tracking.dto.TravelerTrackingView.TrackingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TravelerTrackingQueryTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    private final TripPositionSampleRepository sampleRepository = mock(TripPositionSampleRepository.class);
    private final TrackingEventRepository eventRepository = mock(TrackingEventRepository.class);
    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final TripArrivalInstant arrivalInstant = mock(TripArrivalInstant.class);
    private final TravelerTrackingQuery query = new TravelerTrackingQuery(
            sampleRepository, eventRepository, announcementRepository, arrivalInstant);

    private final UUID annId = UUID.randomUUID();
    private final List<TripPositionSampleEntity> samples = new ArrayList<>();
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        trip = new AnnouncementEntity();
        ReflectionTestUtils.setField(trip, "id", annId);
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(trip));
        when(arrivalInstant.of(any())).thenReturn(Optional.empty());
        when(sampleRepository.findByAnnouncementIdAndCapturedAtBetweenOrderByCapturedAtAsc(
                eq(annId), any(), any())).thenAnswer(inv -> {
            OffsetDateTime from = inv.getArgument(1);
            OffsetDateTime to = inv.getArgument(2);
            return samples.stream()
                    .filter(s -> !s.getCapturedAt().isBefore(from) && !s.getCapturedAt().isAfter(to))
                    .sorted(java.util.Comparator.comparing(TripPositionSampleEntity::getCapturedAt))
                    .toList();
        });
    }

    private BidEntity bid(BidStatus status, OffsetDateTime scannedAt, OffsetDateTime deliveredAt) {
        BidEntity b = new BidEntity();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(b, "id", id);
        b.setAnnouncementId(annId);
        b.setStatus(status);
        if (deliveredAt != null) {
            b.markDelivered(deliveredAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        if (scannedAt != null) {
            TrackingEventEntity e = new TrackingEventEntity();
            e.setEventType(TrackingEventType.DEPART);
            e.setScannedAt(scannedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
            when(eventRepository.findFirstByBidIdAndEventTypeOrderByScannedAtAsc(id, TrackingEventType.DEPART))
                    .thenReturn(Optional.of(e));
        } else {
            when(eventRepository.findFirstByBidIdAndEventTypeOrderByScannedAtAsc(id, TrackingEventType.DEPART))
                    .thenReturn(Optional.empty());
        }
        return b;
    }

    private void sample(OffsetDateTime at, String city, Integer pct) {
        samples.add(new TripPositionSampleEntity(annId, at, city, "FR", pct, SampleSource.OUVERTURE));
    }

    @Test
    void statusRules() {
        BidEntity inTransit = bid(BidStatus.IN_TRANSIT, T0, null);
        // rien : ni départ marqué ni échantillon
        assertThat(query.forSender(inTransit, T0.plusHours(1)).status()).isEqualTo(TrackingStatus.NOT_ACTIVATED);

        // un échantillon dans la fenêtre
        sample(T0.plusMinutes(30), "Lyon", 10);
        assertThat(query.forSender(inTransit, T0.plusHours(1)).status()).isEqualTo(TrackingStatus.EN_ROUTE);

        // départ marqué sans échantillon
        samples.clear();
        trip.setDepartedAt(T0);
        assertThat(query.forSender(inTransit, T0.plusHours(1)).status()).isEqualTo(TrackingStatus.EN_ROUTE);

        // colis arrivé
        BidEntity arrived = bid(BidStatus.ARRIVED, T0, null);
        assertThat(query.forSender(arrived, T0.plusHours(5)).status()).isEqualTo(TrackingStatus.ARRIVED);

        // STALE : 72 h passées l'arrivée prévue, plus rien de récent
        OffsetDateTime eta = T0.plusHours(8);
        when(arrivalInstant.of(any())).thenReturn(Optional.of(eta));
        assertThat(query.forSender(inTransit, eta.plusHours(71)).status()).isEqualTo(TrackingStatus.EN_ROUTE);
        assertThat(query.forSender(inTransit, eta.plusHours(73)).status()).isEqualTo(TrackingStatus.STALE);
        // un échantillon récent (moins de 72 h) l'empêche
        sample(eta.plusHours(60), "Dakar", null);
        assertThat(query.forSender(inTransit, eta.plusHours(73)).status()).isEqualTo(TrackingStatus.EN_ROUTE);
        // ARRIVED l'emporte sur STALE
        assertThat(query.forSender(arrived, eta.plusHours(100)).status()).isEqualTo(TrackingStatus.ARRIVED);
    }

    @Test
    void neverActivatedTripIsNotStaleAfterArrival() {
        when(arrivalInstant.of(any())).thenReturn(Optional.of(T0.plusHours(8)));
        BidEntity b = bid(BidStatus.IN_TRANSIT, T0, null);
        assertThat(query.forSender(b, T0.plusDays(10)).status()).isEqualTo(TrackingStatus.NOT_ACTIVATED);
    }

    @Test
    void senderSeesOnlyOwnWindow() {
        sample(T0.minusHours(1), "Paris", 0);       // avant le scan du colis B, avant celui de A aussi
        sample(T0.plusHours(1), "Lyon", 10);
        sample(T0.plusHours(3), "Marseille", 25);
        sample(T0.plusHours(6), "Madrid", 50);
        BidEntity a = bid(BidStatus.COMPLETED, T0, T0.plusHours(4));        // livré avant Madrid
        BidEntity b = bid(BidStatus.IN_TRANSIT, T0.plusHours(2), null);     // scanné après Lyon

        TravelerTrackingView va = query.forSender(a, T0.plusHours(10));
        assertThat(va.history()).extracting(TravelerTrackingView.HistoryPoint::city)
                .containsExactly("Lyon", "Marseille");
        assertThat(va.nearestCity()).isEqualTo("Marseille");

        TravelerTrackingView vb = query.forSender(b, T0.plusHours(10));
        assertThat(vb.history()).extracting(TravelerTrackingView.HistoryPoint::city)
                .containsExactly("Marseille", "Madrid");
        assertThat(vb.nearestCity()).isEqualTo("Madrid");
        assertThat(vb.progressPct()).isEqualTo(50);
        assertThat(vb.lastUpdatedAt()).isEqualTo(T0.plusHours(6));
    }

    @Test
    void consecutiveSameCityCollapsed() {
        sample(T0.plusMinutes(10), "Paris", 0);
        sample(T0.plusMinutes(30), "Paris", 1);
        sample(T0.plusHours(1), "Lyon", 10);
        sample(T0.plusHours(2), "Paris", 20);   // revient : un point distinct
        BidEntity b = bid(BidStatus.IN_TRANSIT, T0, null);

        TravelerTrackingView v = query.forSender(b, T0.plusHours(3));
        assertThat(v.history()).extracting(TravelerTrackingView.HistoryPoint::city)
                .containsExactly("Paris", "Lyon", "Paris");
        assertThat(v.history().get(0).at()).isEqualTo(T0.plusMinutes(10));
        assertThat(v.progressPct()).isEqualTo(20);
    }

    @Test
    void publicViewHasNoHistory() {
        sample(T0.plusHours(1), "Lyon", 10);
        sample(T0.plusHours(2), "Marseille", 25);
        BidEntity b = bid(BidStatus.IN_TRANSIT, T0, null);

        TravelerTrackingView v = query.forPublic(b, T0.plusHours(3));
        assertThat(v.history()).isEmpty();
        assertThat(v.nearestCity()).isEqualTo("Marseille");
        assertThat(v.status()).isEqualTo(TrackingStatus.EN_ROUTE);
    }

    @Test
    void deliveredParcelStopsShowingNewSamples() {
        sample(T0.plusHours(1), "Lyon", 10);
        BidEntity b = bid(BidStatus.COMPLETED, T0, T0.plusHours(2));
        sample(T0.plusHours(5), "Dakar", 100);   // capté après la livraison de CE colis

        TravelerTrackingView v = query.forSender(b, T0.plusHours(9));
        assertThat(v.nearestCity()).isEqualTo("Lyon");
        assertThat(v.history()).hasSize(1);
        assertThat(query.forPublic(b, T0.plusHours(9)).nearestCity()).isEqualTo("Lyon");
    }

    @Test
    void parcelNotYetScannedHasNoPosition() {
        sample(T0.plusHours(1), "Lyon", 10);
        BidEntity b = bid(BidStatus.ACCEPTED, null, null);
        TravelerTrackingView v = query.forSender(b, T0.plusHours(3));
        assertThat(v.nearestCity()).isNull();
        assertThat(v.history()).isEmpty();
        assertThat(v.status()).isEqualTo(TrackingStatus.NOT_ACTIVATED);
    }
}
