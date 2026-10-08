package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.ParcelUnclaimedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Passage « non réclamé » au terme de la garde (FLUTTER-E2). */
@ExtendWith(MockitoExtension.class)
class UnclaimedParcelSchedulerTest {

    @Mock CancellationRepository cancellationRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock DisputeRepository disputeRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    static final Instant NOW = Instant.parse("2026-10-15T12:30:00Z");
    static final OffsetDateTime NOW_ODT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    UUID bidId = UUID.randomUUID();
    UUID senderId = UUID.randomUUID();
    UUID travelerId = UUID.randomUUID();
    UUID annId = UUID.randomUUID();
    UnclaimedParcelScheduler scheduler;
    CancellationEntity hold;

    @BeforeEach
    void setUp() {
        scheduler = new UnclaimedParcelScheduler(cancellationRepository, bidRepository, announcementRepository,
                disputeRepository, auditService, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
        hold = new CancellationEntity();
        ReflectionTestUtils.setField(hold, "id", UUID.randomUUID());
        hold.setBidId(bidId);
        hold.setScope(CancellationScope.DELIVERY);
        hold.setReason("RECIPIENT_NO_SHOW");
        hold.setNoShowStatus(CancellationStatus.CONFIRMED);
        hold.setHoldUntil(NOW_ODT.minusHours(1));
        when(cancellationRepository.findDueUnclaimedHolds(NOW_ODT)).thenReturn(List.of(hold));
    }

    private void stubBid(BidStatus status) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(annId);
        bid.setStatus(status);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
    }

    private void stubAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", annId);
        a.setTravelerId(travelerId);
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(a));
    }

    @Test
    void gardeEchue_passeNonReclameEtPublieUneFois() {
        stubBid(BidStatus.ARRIVED);
        stubAnnouncement();
        when(cancellationRepository.markUnclaimed(hold.getId(), NOW_ODT)).thenReturn(1);

        scheduler.run();

        verify(eventPublisher).publishEvent(new ParcelUnclaimedEvent(bidId, senderId, travelerId, hold.getId()));
        verify(auditService).log(eq("CANCELLATION"), eq(hold.getId()), eq("PARCEL_UNCLAIMED"), isNull(), any());
    }

    @Test
    void claimPerdu_rienNEstPublie() {
        stubBid(BidStatus.ARRIVED);
        stubAnnouncement();
        when(cancellationRepository.markUnclaimed(any(), any())).thenReturn(0);

        scheduler.run();

        verifyNoInteractions(eventPublisher, auditService);
    }

    @Test
    void colisLivreEntreTemps_aucunVersement() {
        stubBid(BidStatus.COMPLETED);

        scheduler.run();

        verify(cancellationRepository, never()).markUnclaimed(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void litigeSurLeColis_aucunVersementAutomatique() {
        stubBid(BidStatus.ARRIVED);
        when(disputeRepository.existsByBidId(bidId)).thenReturn(true);

        scheduler.run();

        verify(cancellationRepository, never()).markUnclaimed(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void bidOuVoyageurIntrouvable_ignore() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        scheduler.run();

        stubBid(BidStatus.ARRIVED);
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        scheduler.run();

        verify(cancellationRepository, never()).markUnclaimed(any(), any());
        verifyNoInteractions(eventPublisher);
    }
}
