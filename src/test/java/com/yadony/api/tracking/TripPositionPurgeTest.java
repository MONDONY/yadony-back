package com.yadony.api.tracking;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.events.AnnouncementCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Requêtes DELETE réelles (dépôt), service, filet de sécurité et écouteurs d'effacement des positions. */
@DataJpaTest
@ActiveProfiles("test")
class TripPositionPurgeTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private TripPositionSampleRepository repository;
    @Autowired
    private AnnouncementRepository announcementRepository;

    private TripPositionPurgeService service;
    private TripPositionPurgeListener listener;

    @BeforeEach
    void setUp() {
        service = new TripPositionPurgeService(repository, Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        listener = new TripPositionPurgeListener(service);
    }

    private AnnouncementEntity trip(UUID travelerId, AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setAvailableKg(new BigDecimal("20"));
        a.setTotalKg(new BigDecimal("20"));
        a.setReservedKg(BigDecimal.ZERO);
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(status);
        return announcementRepository.saveAndFlush(a);
    }

    private void samples(UUID tripId, OffsetDateTime at, int count) {
        for (int i = 0; i < count; i++) {
            repository.save(new TripPositionSampleEntity(tripId, at.plusMinutes(i), "Lyon", "FR", 10, SampleSource.OUVERTURE));
        }
        repository.flush();
    }

    private long countOf(UUID tripId) {
        return repository.findByAnnouncementIdAndCapturedAtBetweenOrderByCapturedAtAsc(
                tripId, NOW.minusYears(1), NOW.plusYears(1)).size();
    }

    @Test
    void purgeDeletesAllSamplesOfTheTrip() {
        UUID t = trip(UUID.randomUUID(), AnnouncementStatus.COMPLETED).getId();
        samples(t, NOW.minusDays(3), 4);

        assertThat(service.purgeTrip(t)).isEqualTo(4);
        assertThat(countOf(t)).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test
    void purgeOnlyTouchesThatTrip() {
        UUID t1 = trip(UUID.randomUUID(), AnnouncementStatus.COMPLETED).getId();
        UUID t2 = trip(UUID.randomUUID(), AnnouncementStatus.IN_PROGRESS).getId();
        samples(t1, NOW.minusDays(3), 2);
        samples(t2, NOW.minusDays(3), 3);

        service.purgeTrip(t1);

        assertThat(countOf(t1)).isZero();
        assertThat(countOf(t2)).isEqualTo(3);
    }

    @Test
    void purgeIsIdempotent() {
        UUID t = trip(UUID.randomUUID(), AnnouncementStatus.COMPLETED).getId();
        samples(t, NOW.minusDays(3), 2);

        assertThat(service.purgeTrip(t)).isEqualTo(2);
        assertThat(service.purgeTrip(t)).isZero();
        assertThat(service.purgeTrip(UUID.randomUUID())).isZero();
    }

    @Test
    void tripCompletionPublishesEventAndPurges() {
        UUID t = trip(UUID.randomUUID(), AnnouncementStatus.COMPLETED).getId();
        samples(t, NOW.minusDays(3), 3);

        listener.onAnnouncementCompleted(new AnnouncementCompletedEvent(t));

        assertThat(countOf(t)).isZero();
    }

    @Test
    void listenerFailureDoesNotBreakCompletion() {
        TripPositionSampleRepository failing = org.mockito.Mockito.mock(TripPositionSampleRepository.class);
        org.mockito.Mockito.when(failing.eraseByAnnouncementId(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("db down"));
        org.mockito.Mockito.when(failing.eraseByTravelerId(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("db down"));
        TripPositionPurgeListener l = new TripPositionPurgeListener(
                new TripPositionPurgeService(failing, Clock.systemUTC()));

        org.assertj.core.api.Assertions.assertThatCode(
                () -> l.onAnnouncementCompleted(new AnnouncementCompletedEvent(UUID.randomUUID()))).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(
                () -> l.onAccountDeletion(new AccountDeletionRequestedEvent(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    @Test
    void safetyNetDeletesSamplesOfClosedTrips() {
        UUID traveler = UUID.randomUUID();
        UUID done = trip(traveler, AnnouncementStatus.COMPLETED).getId();
        UUID cancelled = trip(traveler, AnnouncementStatus.CANCELLED).getId();
        UUID removed = trip(traveler, AnnouncementStatus.REMOVED_BY_ADMIN).getId();
        UUID active = trip(traveler, AnnouncementStatus.IN_PROGRESS).getId();
        for (UUID id : new UUID[] {done, cancelled, removed, active}) {
            samples(id, NOW.minusDays(2), 2);
        }

        assertThat(service.purgeClosedAndExpired()).isEqualTo(6);

        assertThat(countOf(done)).isZero();
        assertThat(countOf(cancelled)).isZero();
        assertThat(countOf(removed)).isZero();
        assertThat(countOf(active)).isEqualTo(2);
        assertThat(service.purgeClosedAndExpired()).isZero();
    }

    @Test
    void safetyNetDeletesSamplesOlderThanNinetyDays() {
        UUID t = trip(UUID.randomUUID(), AnnouncementStatus.IN_PROGRESS).getId();
        samples(t, NOW.minusDays(TripPositionPurgeService.MAX_SAMPLE_AGE_DAYS + 1), 2);
        samples(t, NOW.minusDays(5), 1);

        assertThat(service.purgeClosedAndExpired()).isEqualTo(2);
        assertThat(countOf(t)).isEqualTo(1);
    }

    @Test
    void safetyNetKeepsSamplesOfActiveTripsUnderCap() {
        UUID t = trip(UUID.randomUUID(), AnnouncementStatus.ACTIVE).getId();
        samples(t, NOW.minusDays(TripPositionPurgeService.MAX_SAMPLE_AGE_DAYS - 1), 3);

        assertThat(service.purgeClosedAndExpired()).isZero();
        assertThat(countOf(t)).isEqualTo(3);
    }

    @Test
    void accountDeletionErasesSamplesOfHisTrips() {
        UUID traveler = UUID.randomUUID();
        UUID t1 = trip(traveler, AnnouncementStatus.CANCELLED).getId();
        UUID t2 = trip(traveler, AnnouncementStatus.IN_PROGRESS).getId();
        UUID other = trip(UUID.randomUUID(), AnnouncementStatus.IN_PROGRESS).getId();
        samples(t1, NOW.minusDays(2), 2);
        samples(t2, NOW.minusDays(2), 2);
        samples(other, NOW.minusDays(2), 2);

        listener.onAccountDeletion(new AccountDeletionRequestedEvent(traveler));

        assertThat(countOf(t1)).isZero();
        assertThat(countOf(t2)).isZero();
        assertThat(countOf(other)).isEqualTo(2);
    }
}
