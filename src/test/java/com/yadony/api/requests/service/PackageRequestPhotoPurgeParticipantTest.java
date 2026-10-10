package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.PackageRequestMediaRetentionHold;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestPhotoEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestPhotoRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PackageRequestPhotoPurgeParticipantTest {

    @Mock PackageRequestPhotoRepository photos;
    @Mock PackageRequestRepository requests;
    @Mock NegotiationThreadRepository threads;
    @Mock BidRepository bids;
    @Mock StorageService storage;
    @Mock AuditService audit;
    @Mock MessagingMediaRetentionHold hold;
    @Mock PackageRequestMediaRetentionHold requestHold;

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 10, 0);
    final UUID requestId = UUID.randomUUID();
    PackageRequestPhotoPurgeParticipant participant;

    @BeforeEach
    void setUp() {
        participant = new PackageRequestPhotoPurgeParticipant(photos, requests, threads, bids, storage, audit, List.of(hold), List.of(requestHold));
        lenient().when(photos.findRequestIdsWithLivePhotos()).thenReturn(List.of(requestId));
    }

    private PackageRequestEntity request(PackageRequestStatus status, int daysSinceUpdate) throws Exception {
        PackageRequestEntity r = new PackageRequestEntity();
        set(PackageRequestEntity.class, r, "status", status);
        set(com.yadony.api.common.BaseEntity.class, r, "updatedAt", NOW.minusDays(daysSinceUpdate));
        set(com.yadony.api.common.BaseEntity.class, r, "id", requestId);
        return r;
    }

    private static void set(Class<?> c, Object o, String f, Object v) throws Exception {
        var field = c.getDeclaredField(f); field.setAccessible(true); field.set(o, v);
    }

    private NegotiationThreadEntity threadWithBid(UUID bidId) {
        NegotiationThreadEntity t = mock(NegotiationThreadEntity.class);
        when(t.getMaterializedBidId()).thenReturn(bidId);
        return t;
    }

    @Test
    void completedRequest_noLiveBid_deletesAndMarks() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 8)));
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of());
        PackageRequestPhotoEntity p = new PackageRequestPhotoEntity(requestId, "package_requests/u/1.jpg", 0);
        when(photos.findByPackageRequestIdOrderByPositionAsc(requestId)).thenReturn(List.of(p));

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        verify(storage).deleteFile("package_requests/u/1.jpg");
        assertThat(p.getPurgedAt()).isEqualTo(NOW);
        assertThat(o.deleted()).isEqualTo(1);
        verify(audit).log(eq("package_request"), eq(requestId), eq("PACKAGE_REQUEST_PHOTOS_PURGED"), isNull(), any());
    }

    @Test
    void openRequest_neverTouched() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.OPEN, 300)));
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void removedByAdminRequest_neverTouched() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.REMOVED_BY_ADMIN, 300)));
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void closedRequestNotYetDue_keepsPhotos() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.EXPIRED, 3)));
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void absentOrSoftDeletedRequest_neverTouched() {
        when(requests.findById(requestId)).thenReturn(Optional.empty());

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void materializedBidNotFound_keepsPhotos() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 30)));
        UUID bidId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithBid(bidId);
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of(thread));
        when(bids.findById(bidId)).thenReturn(Optional.empty());

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void openReportOnRequest_keepsPhotos() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 30)));
        when(requestHold.holds(requestId)).thenReturn(true);

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void noOpenReportOnRequest_purgesNormally() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 30)));
        when(requestHold.holds(requestId)).thenReturn(false);
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of());
        PackageRequestPhotoEntity p = new PackageRequestPhotoEntity(requestId, "package_requests/u/1.jpg", 0);
        when(photos.findByPackageRequestIdOrderByPositionAsc(requestId)).thenReturn(List.of(p));

        assertThat(participant.purgeDue(7, false, 500, NOW).deleted()).isEqualTo(1);
        verify(storage).deleteFile("package_requests/u/1.jpg");
    }

    @Test
    void materializedBidStillInTransit_keepsPhotos() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 30)));
        UUID bidId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithBid(bidId);
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of(thread));
        BidEntity bid = new BidEntity();
        set(BidEntity.class, bid, "status", BidStatus.IN_TRANSIT);
        when(bids.findById(bidId)).thenReturn(Optional.of(bid));

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void heldBid_keepsPhotos() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 30)));
        UUID bidId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithBid(bidId);
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of(thread));
        BidEntity bid = new BidEntity();
        set(BidEntity.class, bid, "status", BidStatus.COMPLETED);
        set(BidEntity.class, bid, "deliveredAt", NOW.minusDays(30));
        when(bids.findById(bidId)).thenReturn(Optional.of(bid));
        when(hold.holds(eq(bidId), anyCollection())).thenReturn(true);

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void dryRun_countsOnly() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.CANCELLED, 8)));
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of());
        PackageRequestPhotoEntity p = new PackageRequestPhotoEntity(requestId, "package_requests/u/1.jpg", 0);
        when(photos.findByPackageRequestIdOrderByPositionAsc(requestId)).thenReturn(List.of(p));

        assertThat(participant.purgeDue(7, true, 500, NOW)).isEqualTo(new PhotoPurgeOutcome(1, 0, 0));
        verifyNoInteractions(storage);
        assertThat(p.getPurgedAt()).isNull();
    }

    @Test
    void noSuchKey_countsAsDeleted_otherFailureKeepsRow() throws Exception {
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 8)));
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of());
        PackageRequestPhotoEntity gone = new PackageRequestPhotoEntity(requestId, "gone.jpg", 0);
        PackageRequestPhotoEntity broken = new PackageRequestPhotoEntity(requestId, "broken.jpg", 1);
        PackageRequestPhotoEntity legacy = new PackageRequestPhotoEntity(requestId, "https://x.test/a.jpg", 2);
        when(photos.findByPackageRequestIdOrderByPositionAsc(requestId)).thenReturn(List.of(gone, broken, legacy));
        doThrow(NoSuchKeyException.builder().build()).when(storage).deleteFile("gone.jpg");
        doThrow(new RuntimeException("r2 down")).when(storage).deleteFile("broken.jpg");

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        assertThat(o).isEqualTo(new PhotoPurgeOutcome(3, 2, 1));
        assertThat(gone.getPurgedAt()).isEqualTo(NOW);
        assertThat(legacy.getPurgedAt()).isEqualTo(NOW);
        assertThat(broken.getPurgedAt()).isNull();
    }

    @Test
    void limit_stopsAfterFirstRequest() throws Exception {
        UUID other = UUID.randomUUID();
        when(photos.findRequestIdsWithLivePhotos()).thenReturn(List.of(requestId, other));
        when(requests.findById(requestId)).thenReturn(Optional.of(request(PackageRequestStatus.COMPLETED, 8)));
        when(threads.findByPackageRequestId(requestId)).thenReturn(List.of());
        PackageRequestPhotoEntity p = new PackageRequestPhotoEntity(requestId, "a.jpg", 0);
        when(photos.findByPackageRequestIdOrderByPositionAsc(requestId)).thenReturn(List.of(p));

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 1, NOW);

        assertThat(o.eligible()).isEqualTo(1);
        verify(requests, never()).findById(other);
        verify(photos, never()).findByPackageRequestIdOrderByPositionAsc(other);
    }
}
