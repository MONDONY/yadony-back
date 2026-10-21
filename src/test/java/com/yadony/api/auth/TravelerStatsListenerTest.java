package com.yadony.api.auth;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.StatsPeriod;
import com.yadony.api.matching.TripsSummaryService;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelerStatsListenerTest {

    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;
    @Mock private CacheManager cacheManager;
    @Mock private Cache tripsSummaryCache;

    private TravelerStatsListener listener;

    @BeforeEach
    void setUp() {
        listener = new TravelerStatsListener(userRepository, bidRepository,
                announcementRepository, auditService, cacheManager);
        lenient().when(cacheManager.getCache(TripsSummaryService.CACHE_NAME)).thenReturn(tripsSummaryCache);
    }

    private static void setEntityId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private BidEntity completedBid(UUID bidId, UUID announcementId) {
        BidEntity bid = new BidEntity();
        setEntityId(bid, bidId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.COMPLETED);
        // Les tests historiques portent sur total_trips : le colis est déjà compté.
        bid.setDeliveryCounted(true);
        return bid;
    }

    private AnnouncementEntity announcement(UUID id, boolean alreadyCounted) {
        AnnouncementEntity a = new AnnouncementEntity();
        setEntityId(a, id);
        a.setTotalTripsCounted(alreadyCounted);
        return a;
    }

    private UserEntity traveler(UUID travelerId, int currentTotal) {
        UserEntity u = new UserEntity();
        setEntityId(u, travelerId);
        u.setTotalTrips(currentTotal);
        return u;
    }

    @Test
    void increments_totalTrips_on_first_bid_completed_for_announcement() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);
        UserEntity user = traveler(travelerId, 4);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        assertThat(user.getTotalTrips()).isEqualTo(5);
        assertThat(ann.isTotalTripsCounted()).isTrue();
        verify(userRepository).save(user);
        verify(announcementRepository).save(ann);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(travelerId), eq("TOTAL_TRIPS_INCREMENTED"),
                eq(travelerId), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("bidId", bidId.toString())
                .containsEntry("announcementId", announcementId.toString())
                .containsEntry("newTotal", "5");
    }

    @Test
    void is_idempotent_when_announcement_already_counted() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void flags_announcement_as_counted_after_increment() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);
        UserEntity user = traveler(travelerId, 0);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        assertThat(ann.isTotalTripsCounted()).isTrue();
        assertThat(user.getTotalTrips()).isEqualTo(1);
    }

    @Test
    void noop_when_bid_not_found() {
        UUID bidId = UUID.randomUUID();
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(announcementRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void noop_when_announcement_not_found() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void traveler_not_found_marks_announcement_counted_and_logs_error() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        assertThat(ann.isTotalTripsCounted()).isTrue();
        verify(announcementRepository).save(ann);
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void skips_increment_if_bid_status_not_completed() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        bid.setStatus(BidStatus.ACCEPTED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(announcementRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void evicts_trips_summary_cache_after_increment() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, false);
        UserEntity user = traveler(travelerId, 2);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        // La clé inclut la période : chaque entrée du voyageur doit être évincée,
        // sinon un seul intervalle refléterait la livraison.
        for (StatsPeriod period : StatsPeriod.values()) {
            verify(tripsSummaryCache).evict(StatsPeriod.cacheKey(travelerId, period));
        }
    }

    @Test
    void does_not_evict_cache_when_already_counted() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(tripsSummaryCache, never()).evict(any());
    }

    @Test
    void onDeliveryConfirmed_incrementsTotalDeliveries_once() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        bid.setDeliveryCounted(false);
        AnnouncementEntity ann = announcement(announcementId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        // Le drapeau n'est posé qu'une fois : l'événement rejoué reçoit 0 ligne.
        when(bidRepository.markDeliveryCounted(bidId)).thenReturn(1, 0);
        when(userRepository.incrementTotalDeliveries(travelerId)).thenReturn(1);

        DeliveryConfirmedEvent event = new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);
        listener.onDeliveryConfirmed(event);
        listener.onDeliveryConfirmed(event);

        verify(bidRepository, times(2)).markDeliveryCounted(bidId);
        verify(userRepository, times(1)).incrementTotalDeliveries(travelerId);
        // Plus de lire-puis-écrire : ni voyageur relu pour le compteur, ni bid sauvegardé.
        verify(bidRepository, never()).save(any());
        // total_trips intact : le trajet était déjà compté.
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void onDeliveryConfirmed_countsDeliveryBeforeLoadingTraveler_forTotalTrips() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        bid.setDeliveryCounted(false);
        AnnouncementEntity ann = announcement(announcementId, false);
        UserEntity user = traveler(travelerId, 0);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(user));
        when(bidRepository.markDeliveryCounted(bidId)).thenReturn(1);
        when(userRepository.incrementTotalDeliveries(travelerId)).thenReturn(1);

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        assertThat(user.getTotalTrips()).isEqualTo(1);
        // Les UPDATE atomiques passent avant la lecture du voyageur qui sert à total_trips.
        var order = inOrder(bidRepository, userRepository);
        order.verify(bidRepository).markDeliveryCounted(bidId);
        order.verify(userRepository).incrementTotalDeliveries(travelerId);
        order.verify(userRepository).findById(travelerId);
        order.verify(userRepository).save(user);
    }

    @Test
    void onDeliveryConfirmed_travelerMissing_deliveryFlagSetWithoutIncrement() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        bid.setDeliveryCounted(false);
        AnnouncementEntity ann = announcement(announcementId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.markDeliveryCounted(bidId)).thenReturn(1);
        when(userRepository.incrementTotalDeliveries(travelerId)).thenReturn(0);

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(userRepository).incrementTotalDeliveries(travelerId);
        verify(userRepository, never()).save(any());
    }

    @Test
    void onDeliveryConfirmed_notCompleted_noIncrement() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, UUID.randomUUID());
        bid.setDeliveryCounted(false);
        bid.setStatus(BidStatus.ACCEPTED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId));

        verify(bidRepository, never()).markDeliveryCounted(any());
        verify(userRepository, never()).incrementTotalDeliveries(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void onDeliveryConfirmed_alreadyCountedInMemory_skipsUpdates() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, announcementId);
        AnnouncementEntity ann = announcement(announcementId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(bidRepository, never()).markDeliveryCounted(any());
        verify(userRepository, never()).incrementTotalDeliveries(any());
    }
}
