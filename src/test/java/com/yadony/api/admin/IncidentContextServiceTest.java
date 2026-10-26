package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminIncidentContextResponse;
import com.yadony.api.admin.dto.AdminIncidentContextResponse.RelatedIncident;
import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.cancellation.NoShowAdminDecision;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentContextServiceTest {

    @Mock DisputeRepository disputeRepo;
    @Mock CancellationRepository cancellationRepo;
    @Mock BidRepository bidRepo;
    @Mock AnnouncementRepository announcementRepo;
    @Mock ReportContextService reportContext;

    private final UUID sender = UUID.randomUUID();
    private final UUID traveler = UUID.randomUUID();

    private IncidentContextService service() {
        return new IncidentContextService(disputeRepo, cancellationRepo, bidRepo, announcementRepo, reportContext);
    }

    private DisputeEntity dispute(UUID id, String type, String status, LocalDateTime createdAt) {
        DisputeEntity d = new DisputeEntity();
        ReflectionTestUtils.setField(d, "id", id);
        ReflectionTestUtils.setField(d, "createdAt", createdAt);
        d.setType(type);
        d.setStatus(status);
        d.setSenderId(sender);
        d.setTravelerId(traveler);
        return d;
    }

    private CancellationEntity noShow(UUID id, String reason, CancellationScope scope, CancellationStatus status, LocalDateTime createdAt) {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "createdAt", createdAt);
        c.setReason(reason);
        c.setScope(scope);
        c.setNoShowStatus(status);
        return c;
    }

    private static <T> PageImpl<T> page(List<T> content) {
        return new PageImpl<>(content);
    }

    @Test
    void forDispute_listsTheOtherIncidentsOfBothPartiesNewestFirst() {
        UUID id = UUID.randomUUID();
        DisputeEntity self = dispute(id, "TRAVELER_DELIVERY_NO_SHOW", "OPEN", LocalDateTime.parse("2026-10-17T10:00:00"));
        DisputeEntity older = dispute(UUID.randomUUID(), "ADMIN_PARCEL_LOST", "RESOLVED", LocalDateTime.parse("2026-09-02T10:00:00"));
        CancellationEntity absence = noShow(UUID.randomUUID(), "SENDER_NO_SHOW", CancellationScope.HANDOVER,
                CancellationStatus.PENDING_CONFIRMATION, LocalDateTime.parse("2026-10-10T10:00:00"));
        when(disputeRepo.findAdminByParty(eq(sender), any(Pageable.class))).thenReturn(page(List.of(self, older)));
        when(disputeRepo.findAdminByParty(eq(traveler), any(Pageable.class))).thenReturn(page(List.of(self)));
        when(cancellationRepo.findAdminByParty(eq(sender), any(Pageable.class))).thenReturn(page(List.of(absence)));
        when(cancellationRepo.findAdminByParty(eq(traveler), any(Pageable.class))).thenReturn(page(List.of(absence)));
        List<HistoryEntry> history = List.of(new HistoryEntry("RESOLVE", null, LocalDateTime.now(), "moussa@yadony.com", "ok"));
        when(reportContext.history("DISPUTE", id)).thenReturn(history);

        AdminIncidentContextResponse ctx = service().forDispute(self);

        assertThat(ctx.related()).extracting(RelatedIncident::id).containsExactly(absence.getId(), older.getId());
        assertThat(ctx.related().get(0)).extracting(RelatedIncident::kind, RelatedIncident::scope, RelatedIncident::open)
                .containsExactly("NOSHOW", "HANDOVER", true);
        assertThat(ctx.related().get(1)).extracting(RelatedIncident::kind, RelatedIncident::type, RelatedIncident::open, RelatedIncident::scope)
                .containsExactly("DISPUTE", "ADMIN_PARCEL_LOST", false, null);
        assertThat(ctx.history()).isEqualTo(history);
    }

    @Test
    void forDispute_ignoresCancellationsThatAreNotNoShowDeclarations() {
        UUID id = UUID.randomUUID();
        DisputeEntity self = dispute(id, "ADMIN_OTHER", "OPEN", LocalDateTime.now());
        CancellationEntity cancelled = noShow(UUID.randomUUID(), "TRAVELER_CANCELLED", CancellationScope.HANDOVER,
                CancellationStatus.CONFIRMED, LocalDateTime.now());
        when(disputeRepo.findAdminByParty(any(), any(Pageable.class))).thenReturn(page(List.of()));
        when(cancellationRepo.findAdminByParty(any(), any(Pageable.class))).thenReturn(page(List.of(cancelled)));
        when(reportContext.history("DISPUTE", id)).thenReturn(List.of());

        assertThat(service().forDispute(self).related()).isEmpty();
    }

    @Test
    void forDispute_withoutPartiesAsksNothing() {
        UUID id = UUID.randomUUID();
        DisputeEntity self = new DisputeEntity();
        ReflectionTestUtils.setField(self, "id", id);
        when(reportContext.history("DISPUTE", id)).thenReturn(List.of());

        assertThat(service().forDispute(self).related()).isEmpty();
        verify(disputeRepo, never()).findAdminByParty(any(), any(Pageable.class));
    }

    @Test
    void forCancellation_findsThePartiesThroughTheBidAndTheAnnouncement() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        CancellationEntity self = noShow(id, "TRAVELER_DELIVERY_NO_SHOW", CancellationScope.DELIVERY,
                CancellationStatus.CONTESTED, LocalDateTime.parse("2026-10-17T10:00:00"));
        self.setBidId(bidId);
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "senderId", sender);
        ReflectionTestUtils.setField(bid, "announcementId", announcementId);
        AnnouncementEntity announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "travelerId", traveler);
        when(bidRepo.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(announcement));
        DisputeEntity linked = dispute(UUID.randomUUID(), "TRAVELER_DELIVERY_NO_SHOW", "OPEN", LocalDateTime.parse("2026-10-18T10:00:00"));
        when(disputeRepo.findAdminByParty(any(), any(Pageable.class))).thenReturn(page(List.of(linked)));
        when(cancellationRepo.findAdminByParty(any(), any(Pageable.class))).thenReturn(page(List.of(self)));
        when(reportContext.history("CANCELLATION", id)).thenReturn(List.of());

        AdminIncidentContextResponse ctx = service().forCancellation(self);

        assertThat(ctx.related()).extracting(RelatedIncident::id).containsExactly(linked.getId());
        verify(disputeRepo).findAdminByParty(eq(sender), any(Pageable.class));
        verify(disputeRepo).findAdminByParty(eq(traveler), any(Pageable.class));
    }

    @Test
    void forCancellation_decidedByAnAdminIsNotOpen_andMissingBidMeansNoParty() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        CancellationEntity self = noShow(id, "SENDER_NO_SHOW", CancellationScope.HANDOVER, CancellationStatus.CONFIRMED, LocalDateTime.now());
        self.setBidId(bidId);
        when(bidRepo.findById(bidId)).thenReturn(Optional.empty());
        when(reportContext.history("CANCELLATION", id)).thenReturn(List.of());

        assertThat(service().forCancellation(self).related()).isEmpty();
        verify(disputeRepo, never()).findAdminByParty(any(), any(Pageable.class));
    }

    @Test
    void related_anAbsenceAlreadyDecidedByAnAdminIsNotOpen_andIsCappedAtTen() {
        UUID id = UUID.randomUUID();
        DisputeEntity self = dispute(id, "ADMIN_OTHER", "OPEN", LocalDateTime.parse("2026-10-17T10:00:00"));
        CancellationEntity decided = noShow(UUID.randomUUID(), "SENDER_NO_SHOW", CancellationScope.HANDOVER,
                CancellationStatus.PENDING_CONFIRMATION, LocalDateTime.parse("2026-10-16T10:00:00"));
        decided.setAdminDecision(NoShowAdminDecision.REJECTED);
        List<DisputeEntity> many = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> dispute(UUID.randomUUID(), "ADMIN_OTHER", "RESOLVED", LocalDateTime.parse("2026-09-01T10:00:00").plusDays(i)))
                .toList();
        when(disputeRepo.findAdminByParty(any(), any(Pageable.class))).thenReturn(page(many));
        when(cancellationRepo.findAdminByParty(any(), any(Pageable.class))).thenReturn(page(List.of(decided)));
        when(reportContext.history("DISPUTE", id)).thenReturn(List.of());

        List<RelatedIncident> related = service().forDispute(self).related();

        assertThat(related).hasSize(10);
        assertThat(related.get(0).kind()).isEqualTo("NOSHOW");
        assertThat(related.get(0).open()).isFalse();
    }
}
