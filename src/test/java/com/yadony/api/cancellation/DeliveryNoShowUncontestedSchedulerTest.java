package com.yadony.api.cancellation;

import com.yadony.api.common.AuditService;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Le brief de référence (task-A6-brief.md) suggère d'appeler directement
 * {@code DisputeService.openDeliveryNoShowDispute} depuis ce scheduler. On en
 * dévie ici : CLAUDE.md interdit l'injection de service cross-package
 * (cancellation → disputes) et impose les Spring Events pour ce cas — règle déjà
 * respectée par le chemin "contesté" analogue ({@code CancellationService
 * .contestDeliveryNoShow} publie {@code DisputeOpenedEvent}, consommé par
 * {@code DisputeOpenedEventListener} qui appelle lui-même
 * {@code disputeService.openDeliveryNoShowDispute}). Ce scheduler réutilise donc
 * le même event pour le chemin "non contesté", ce qui le fait converger sur le
 * même point d'entrée (idempotent par (bidId, type) côté DisputeService) tout en
 * respectant la séparation de packages.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryNoShowUncontestedSchedulerTest {

    @Mock CancellationRepository cancellationRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;

    DeliveryNoShowUncontestedScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DeliveryNoShowUncontestedScheduler(
                cancellationRepository, bidRepository, announcementRepository, eventPublisher, auditService);
    }

    private CancellationEntity pendingDelivery(UUID bidId, String reason) {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setBidId(bidId);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason(reason);
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setContestationDeadline(OffsetDateTime.now().minusHours(1));
        return c;
    }

    @Test
    void run_opensUncontestedDisputeForExpiredRecipientNoShow() {
        UUID bidId = UUID.randomUUID();
        CancellationEntity c = pendingDelivery(bidId, "RECIPIENT_NO_SHOW");
        when(cancellationRepository.findExpiredPendingByScope(eq(CancellationScope.DELIVERY), any()))
                .thenReturn(List.of(c));

        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        UUID senderId = UUID.randomUUID();
        bid.setSenderId(senderId);
        UUID annId = UUID.randomUUID();
        bid.setAnnouncementId(annId);
        when(bidRepository.findAllById(List.of(bidId))).thenReturn(List.of(bid));

        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", annId);
        UUID travelerId = UUID.randomUUID();
        ann.setTravelerId(travelerId);
        when(announcementRepository.findAllById(List.of(annId))).thenReturn(List.of(ann));

        scheduler.run();

        ArgumentCaptor<DisputeOpenedEvent> captor = ArgumentCaptor.forClass(DisputeOpenedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        DisputeOpenedEvent event = captor.getValue();
        assertThat(event.getBidId()).isEqualTo(bidId);
        assertThat(event.getSenderId()).isEqualTo(senderId);
        assertThat(event.getTravelerId()).isEqualTo(travelerId);
        assertThat(event.getType()).isEqualTo("RECIPIENT_NO_SHOW");

        // Idempotence : le job marque l'entité pour qu'elle ne réapparaisse plus dans
        // findExpiredPendingByScope (qui ne sélectionne que PENDING_CONFIRMATION) —
        // sinon l'event (donc les notifications + métriques) serait republié à
        // chaque exécution horaire tant que l'admin n'a pas tranché le litige.
        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
        verify(cancellationRepository).save(c);

        verify(auditService).log(eq("CANCELLATION"), eq(c.getId()), anyString(), any(), any());
    }

    @Test
    void run_opensUncontestedDisputeForExpiredTravelerDeliveryNoShow() {
        UUID bidId = UUID.randomUUID();
        CancellationEntity c = pendingDelivery(bidId, "TRAVELER_DELIVERY_NO_SHOW");
        when(cancellationRepository.findExpiredPendingByScope(eq(CancellationScope.DELIVERY), any()))
                .thenReturn(List.of(c));

        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        UUID senderId = UUID.randomUUID();
        bid.setSenderId(senderId);
        UUID annId = UUID.randomUUID();
        bid.setAnnouncementId(annId);
        when(bidRepository.findAllById(List.of(bidId))).thenReturn(List.of(bid));

        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", annId);
        UUID travelerId = UUID.randomUUID();
        ann.setTravelerId(travelerId);
        when(announcementRepository.findAllById(List.of(annId))).thenReturn(List.of(ann));

        scheduler.run();

        ArgumentCaptor<DisputeOpenedEvent> captor = ArgumentCaptor.forClass(DisputeOpenedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("TRAVELER_DELIVERY_NO_SHOW");
    }

    @Test
    void run_isIdempotent_noExpiredEntities() {
        when(cancellationRepository.findExpiredPendingByScope(eq(CancellationScope.DELIVERY), any()))
                .thenReturn(List.of());

        scheduler.run();

        verifyNoInteractions(eventPublisher, bidRepository, announcementRepository, auditService);
        verify(cancellationRepository, never()).save(any());
    }

    @Test
    void run_skipsEntityWhenBidMissing() {
        UUID bidId = UUID.randomUUID();
        CancellationEntity c = pendingDelivery(bidId, "RECIPIENT_NO_SHOW");
        when(cancellationRepository.findExpiredPendingByScope(eq(CancellationScope.DELIVERY), any()))
                .thenReturn(List.of(c));
        when(bidRepository.findAllById(List.of(bidId))).thenReturn(List.of());

        scheduler.run();

        verifyNoInteractions(eventPublisher, announcementRepository, auditService);
        verify(cancellationRepository, never()).save(any());
    }

    @Test
    void run_skipsEntityWhenAnnouncementMissing() {
        UUID bidId = UUID.randomUUID();
        CancellationEntity c = pendingDelivery(bidId, "RECIPIENT_NO_SHOW");
        when(cancellationRepository.findExpiredPendingByScope(eq(CancellationScope.DELIVERY), any()))
                .thenReturn(List.of(c));

        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(UUID.randomUUID());
        UUID annId = UUID.randomUUID();
        bid.setAnnouncementId(annId);
        when(bidRepository.findAllById(List.of(bidId))).thenReturn(List.of(bid));
        when(announcementRepository.findAllById(List.of(annId))).thenReturn(List.of());

        scheduler.run();

        verifyNoInteractions(eventPublisher, auditService);
        verify(cancellationRepository, never()).save(any());
    }

    /** FLUTTER-E2 : un signalement fait avec la procédure (garde) n'ouvre plus de litige. */
    @Test
    void signalementAvecGarde_confirmeSansLitige() {
        UUID bidId = UUID.randomUUID();
        CancellationEntity c = pendingDelivery(bidId, "RECIPIENT_NO_SHOW");
        c.setHoldUntil(OffsetDateTime.now().plusDays(6));

        scheduler.openUncontestedDispute(c, new BidEntity(), new AnnouncementEntity());

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
        verify(cancellationRepository).save(c);
        verify(eventPublisher, never()).publishEvent(any());
        verify(auditService).log(eq("CANCELLATION"), eq(c.getId()),
                eq("DELIVERY_NOSHOW_UNCONTESTED_HOLD_CONTINUES"), isNull(), any());
    }
}
