package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.BidLostRematchPreparedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidLostRematchListener — tests unitaires")
class BidLostRematchListenerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchService rematchService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BidLostRematchListener listener;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new BidLostRematchListener(
                bidRepository, announcementRepository, cancellationRepository, rematchService, eventPublisher);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BidEntity buildBid() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setWeightKg(BigDecimal.valueOf(5));
        setId(b, BID_ID);
        return b;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    @Test
    @DisplayName("event éligible, reason CANCELLED_BY_TRAVELER → cancellation créée + generateForCancellations appelé avec args exacts + event publié")
    void onBidRejected_eligibleCancel_createsCancellationAndPublishesPrepared() {
        BidEntity bid = buildBid();
        AnnouncementEntity announcement = buildAnnouncement();
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, "CANCELLED_BY_TRAVELER", ANNOUNCEMENT_ID, true);

        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        UUID cancellationId = UUID.randomUUID();
        when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
            CancellationEntity c = inv.getArgument(0);
            setId(c, cancellationId);
            return c;
        });

        RematchService.RematchInfo info = new RematchService.RematchInfo(cancellationId, 3);
        when(rematchService.generateForCancellations(eq(announcement), any(), any()))
                .thenReturn(Map.of(SENDER_ID, info));

        listener.onBidRejected(event);

        ArgumentCaptor<CancellationEntity> cancellationCaptor = ArgumentCaptor.forClass(CancellationEntity.class);
        verify(cancellationRepository).save(cancellationCaptor.capture());
        CancellationEntity savedCancellation = cancellationCaptor.getValue();
        assertThat(savedCancellation.getBidId()).isEqualTo(BID_ID);
        assertThat(savedCancellation.getCancelledBy()).isEqualTo(TRAVELER_ID);
        assertThat(savedCancellation.getReason()).isEqualTo(CancellationReason.BID_CANCELLED_BY_TRAVELER.name());
        assertThat(savedCancellation.getScope()).isEqualTo(CancellationScope.HANDOVER);

        ArgumentCaptor<AnnouncementEntity> announcementCaptor = ArgumentCaptor.forClass(AnnouncementEntity.class);
        ArgumentCaptor<List<BidEntity>> bidsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<CancellationEntity>> cancellationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(rematchService).generateForCancellations(
                announcementCaptor.capture(), bidsCaptor.capture(), cancellationsCaptor.capture());
        assertThat(announcementCaptor.getValue()).isSameAs(announcement);
        assertThat(bidsCaptor.getValue()).containsExactly(bid);
        assertThat(cancellationsCaptor.getValue()).containsExactly(savedCancellation);

        ArgumentCaptor<BidLostRematchPreparedEvent> preparedCaptor =
                ArgumentCaptor.forClass(BidLostRematchPreparedEvent.class);
        verify(eventPublisher).publishEvent(preparedCaptor.capture());
        BidLostRematchPreparedEvent published = preparedCaptor.getValue();
        assertThat(published.senderId()).isEqualTo(SENDER_ID);
        assertThat(published.bidId()).isEqualTo(BID_ID);
        assertThat(published.cancellationId()).isEqualTo(cancellationId);
        assertThat(published.suggestionCount()).isEqualTo(3);
        assertThat(published.cancelledByTraveler()).isTrue();
    }

    @Test
    @DisplayName("event éligible, reason ≠ CANCELLED_BY_TRAVELER (refus payé) → reason BID_REJECTED_AFTER_PAYMENT, cancelledByTraveler=false")
    void onBidRejected_eligibleReject_usesRejectReason() {
        BidEntity bid = buildBid();
        AnnouncementEntity announcement = buildAnnouncement();
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, "TRAVELER_REJECTED", ANNOUNCEMENT_ID, true);

        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        UUID cancellationId = UUID.randomUUID();
        when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
            CancellationEntity c = inv.getArgument(0);
            setId(c, cancellationId);
            return c;
        });
        when(rematchService.generateForCancellations(any(), any(), any()))
                .thenReturn(Map.of(SENDER_ID, new RematchService.RematchInfo(cancellationId, 1)));

        listener.onBidRejected(event);

        ArgumentCaptor<CancellationEntity> cancellationCaptor = ArgumentCaptor.forClass(CancellationEntity.class);
        verify(cancellationRepository).save(cancellationCaptor.capture());
        assertThat(cancellationCaptor.getValue().getReason())
                .isEqualTo(CancellationReason.BID_REJECTED_AFTER_PAYMENT.name());

        ArgumentCaptor<BidLostRematchPreparedEvent> preparedCaptor =
                ArgumentCaptor.forClass(BidLostRematchPreparedEvent.class);
        verify(eventPublisher).publishEvent(preparedCaptor.capture());
        assertThat(preparedCaptor.getValue().cancelledByTraveler()).isFalse();
    }

    // Lot B (revue round 3) : ANNOUNCEMENT_DELETED (removeByAdmin rematchEligible=false ne
    // passe jamais ici — seul deleteAnnouncement, rematchEligible=true, l'atteint) rejoint
    // désormais l'ensemble « motifs initiés par le voyageur » — même traitement rematch que
    // CANCELLED_BY_TRAVELER, mais le motif brut doit être propagé pour que
    // NotificationDispatcher choisisse le bon libellé (« Trajet supprimé », pas « Transport
    // annulé »).
    @Test
    @DisplayName("event éligible, reason ANNOUNCEMENT_DELETED (suppression de trajet) → " +
            "traité comme « initié par le voyageur », reason propagé sur l'event publié")
    void onBidRejected_announcementDeleted_treatedAsTravelerInitiated() {
        BidEntity bid = buildBid();
        AnnouncementEntity announcement = buildAnnouncement();
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, BidEntity.REJECTION_ANNOUNCEMENT_DELETED, ANNOUNCEMENT_ID, true);

        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        UUID cancellationId = UUID.randomUUID();
        when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
            CancellationEntity c = inv.getArgument(0);
            setId(c, cancellationId);
            return c;
        });
        when(rematchService.generateForCancellations(any(), any(), any()))
                .thenReturn(Map.of(SENDER_ID, new RematchService.RematchInfo(cancellationId, 2)));

        listener.onBidRejected(event);

        ArgumentCaptor<BidLostRematchPreparedEvent> preparedCaptor =
                ArgumentCaptor.forClass(BidLostRematchPreparedEvent.class);
        verify(eventPublisher).publishEvent(preparedCaptor.capture());
        BidLostRematchPreparedEvent published = preparedCaptor.getValue();
        assertThat(published.cancelledByTraveler()).isTrue();
        assertThat(published.reason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);
    }

    @Test
    @DisplayName("event non éligible → aucune interaction avec repositories/rematchService, aucun event publié")
    void onBidRejected_notEligible_doesNothing() {
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, "SENDER_CANCELLED", ANNOUNCEMENT_ID, false);

        listener.onBidRejected(event);

        verifyNoInteractions(bidRepository, announcementRepository, cancellationRepository,
                rematchService, eventPublisher);
    }

    @Test
    @DisplayName("count 0 dans la map retournée → event publié avec suggestionCount=0")
    void onBidRejected_zeroSuggestions_publishesCountZero() {
        BidEntity bid = buildBid();
        AnnouncementEntity announcement = buildAnnouncement();
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, "CANCELLED_BY_TRAVELER", ANNOUNCEMENT_ID, true);

        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        UUID cancellationId = UUID.randomUUID();
        when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
            CancellationEntity c = inv.getArgument(0);
            setId(c, cancellationId);
            return c;
        });
        when(rematchService.generateForCancellations(any(), any(), any()))
                .thenReturn(Map.of(SENDER_ID, new RematchService.RematchInfo(cancellationId, 0)));

        listener.onBidRejected(event);

        ArgumentCaptor<BidLostRematchPreparedEvent> preparedCaptor =
                ArgumentCaptor.forClass(BidLostRematchPreparedEvent.class);
        verify(eventPublisher).publishEvent(preparedCaptor.capture());
        assertThat(preparedCaptor.getValue().suggestionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("annonce introuvable → pas d'exception, pas de cancellation créée, aucun event publié")
    void onBidRejected_missingAnnouncement_doesNotThrow() {
        BidEntity bid = buildBid();
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, "CANCELLED_BY_TRAVELER", ANNOUNCEMENT_ID, true);

        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

        listener.onBidRejected(event);

        verify(cancellationRepository, never()).save(any());
        verifyNoInteractions(rematchService, eventPublisher);
    }

    @Test
    @DisplayName("bid introuvable → pas d'exception, pas de save, pas de generateForCancellations, aucun event publié")
    void onBidRejected_missingBid_logsAndReturns() {
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, "CANCELLED_BY_TRAVELER", ANNOUNCEMENT_ID, true);

        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());

        listener.onBidRejected(event);

        verify(cancellationRepository, never()).save(any());
        verifyNoInteractions(announcementRepository, rematchService, eventPublisher);
    }

    @Test
    @DisplayName("cancellation HANDOVER déjà existante (ex. no-show en cours) → pas de nouvelle cancellation, "
            + "generateForCancellations non appelé, event publié avec cancellationId null + count 0")
    void onBidRejected_existingHandoverCancellation_skipsCreationAndPublishesCountZero() {
        CancellationEntity existing = new CancellationEntity();
        existing.setBidId(BID_ID);
        BidRejectedEvent event = new BidRejectedEvent(
                BID_ID, SENDER_ID, "CANCELLED_BY_TRAVELER", ANNOUNCEMENT_ID, true);

        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(existing));

        listener.onBidRejected(event);

        verify(cancellationRepository, never()).save(any());
        verifyNoInteractions(bidRepository, announcementRepository, rematchService);

        ArgumentCaptor<BidLostRematchPreparedEvent> preparedCaptor =
                ArgumentCaptor.forClass(BidLostRematchPreparedEvent.class);
        verify(eventPublisher).publishEvent(preparedCaptor.capture());
        BidLostRematchPreparedEvent published = preparedCaptor.getValue();
        assertThat(published.senderId()).isEqualTo(SENDER_ID);
        assertThat(published.bidId()).isEqualTo(BID_ID);
        assertThat(published.cancellationId()).isNull();
        assertThat(published.suggestionCount()).isEqualTo(0);
        assertThat(published.cancelledByTraveler()).isTrue();
    }
}
