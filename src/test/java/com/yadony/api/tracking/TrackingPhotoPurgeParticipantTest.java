package com.yadony.api.tracking;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
import com.yadony.api.common.media.RecordingTransactionManager;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackingPhotoPurgeParticipantTest {

    @Mock TrackingEventRepository events;
    @Mock BidRepository bids;
    @Mock StorageService storage;
    @Mock AuditService audit;
    @Mock MessagingMediaRetentionHold hold;

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 10, 0);
    final UUID bidId = UUID.randomUUID();
    final RecordingTransactionManager txm = new RecordingTransactionManager();
    TrackingPhotoPurgeParticipant participant;

    @BeforeEach
    void setUp() {
        participant = new TrackingPhotoPurgeParticipant(events, bids, storage, audit, List.of(hold), txm);
        lenient().when(events.findBidIdsWithLivePhotos()).thenReturn(List.of(bidId));
    }

    private BidEntity completedDaysAgo(int days) {
        BidEntity b = new BidEntity();
        b.setStatus(BidStatus.COMPLETED);
        b.markDelivered(NOW.minusDays(days));
        return b;
    }

    private TrackingEventEntity photo(String key) {
        TrackingEventEntity e = new TrackingEventEntity();
        e.setBidId(bidId);
        e.setPhotoUrl(key);
        return e;
    }

    @Test
    void deliveredLongAgo_deletesAndMarks() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        TrackingEventEntity e = photo("tracking/" + bidId + "/1.jpg");
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(e));

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        verify(storage).deleteFile("tracking/" + bidId + "/1.jpg");
        assertThat(e.getPhotoUrl()).isNull();
        assertThat(e.getPhotoPurgedAt()).isEqualTo(NOW);
        assertThat(o).isEqualTo(new PhotoPurgeOutcome(1, 1, 0));
        verify(audit).log(eq("bid"), eq(bidId), eq("TRACKING_PHOTOS_PURGED"), isNull(), anyMap());
    }

    @Test
    void bidNotFound_keepsPhotos() {
        when(bids.findById(bidId)).thenReturn(Optional.empty());
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void deliveredRecently_untouched() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(3)));
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void openDisputeOrReport_holdsPhotos_evenAfter30Days() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(30)));
        when(hold.holds(eq(bidId), anyCollection())).thenReturn(true);
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verifyNoInteractions(storage);
    }

    @Test
    void dryRun_countsButNeverDeletesOrMarks() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        TrackingEventEntity e = photo("tracking/" + bidId + "/1.jpg");
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(e));

        PhotoPurgeOutcome o = participant.purgeDue(7, true, 500, NOW);

        assertThat(o).isEqualTo(new PhotoPurgeOutcome(1, 0, 0));
        verifyNoInteractions(storage);
        assertThat(e.getPhotoUrl()).isNotNull();
        verify(events, never()).save(any());
    }

    @Test
    void r2Failure_leavesRowForRetry_andCountsFailure() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        TrackingEventEntity e = photo("tracking/" + bidId + "/1.jpg");
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(e));
        doThrow(new RuntimeException("503")).when(storage).deleteFile(any());

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(new PhotoPurgeOutcome(1, 0, 1));
        assertThat(e.getPhotoUrl()).isNotNull();
    }

    @Test
    void objectAlreadyGone_countsAsDeleted() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        TrackingEventEntity e = photo("tracking/" + bidId + "/1.jpg");
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(e));
        doThrow(NoSuchKeyException.builder().build()).when(storage).deleteFile(any());

        assertThat(participant.purgeDue(7, false, 500, NOW).deleted()).isEqualTo(1);
        assertThat(e.getPhotoUrl()).isNull();
    }

    @Test
    void legacyHttpUrl_isMarkedWithoutR2Call() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        TrackingEventEntity e = photo("https://ancien.example/p.jpg");
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(e));

        participant.purgeDue(7, false, 500, NOW);

        verifyNoInteractions(storage);
        assertThat(e.getPhotoUrl()).isNull();
    }

    @Test
    void appelsR2_horsTransaction_marquageEtAuditEnTransactionCourte() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        TrackingEventEntity a = photo("tracking/" + bidId + "/a.jpg");
        TrackingEventEntity b = photo("tracking/" + bidId + "/b.jpg");
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(a, b));
        List<Boolean> txPendantR2 = new ArrayList<>();
        List<Boolean> txPendantEcriture = new ArrayList<>();
        doAnswer(inv -> txPendantR2.add(RecordingTransactionManager.active())).when(storage).deleteFile(any());
        when(events.save(any())).thenAnswer(inv -> {
            txPendantEcriture.add(RecordingTransactionManager.active());
            return inv.getArgument(0);
        });
        doAnswer(inv -> txPendantEcriture.add(RecordingTransactionManager.active()))
                .when(audit).log(any(), any(), any(), any(), anyMap());

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(new PhotoPurgeOutcome(2, 2, 0));

        assertThat(txPendantR2).containsExactly(false, false);
        assertThat(txPendantEcriture).containsExactly(true, true, true);
        assertThat(txm.begun()).isEqualTo(1);
    }

    @Test
    void echecPartielR2_marqueSeulementLesPhotosSupprimees() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        TrackingEventEntity bad = photo("tracking/" + bidId + "/bad.jpg");
        TrackingEventEntity ok = photo("tracking/" + bidId + "/ok.jpg");
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(bad, ok));
        doThrow(new RuntimeException("503")).when(storage).deleteFile("tracking/" + bidId + "/bad.jpg");

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(new PhotoPurgeOutcome(2, 1, 1));
        assertThat(bad.getPhotoUrl()).isNotNull();
        assertThat(ok.getPhotoUrl()).isNull();
        verify(events, never()).save(bad);
        verify(audit).log("bid", bidId, "TRACKING_PHOTOS_PURGED", null,
                java.util.Map.of("count", 1, "reason", "RETENTION_EXPIRED"));
    }

    @Test
    void toutEnEchec_aucuneTransactionOuverte() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        when(events.findLivePhotosByBidId(bidId)).thenReturn(List.of(photo("tracking/" + bidId + "/1.jpg")));
        doThrow(new RuntimeException("503")).when(storage).deleteFile(any());

        participant.purgeDue(7, false, 500, NOW);

        assertThat(txm.begun()).isZero();
        verifyNoInteractions(audit);
    }

    @Test
    void purgeDue_nEstPasTransactionnel() throws Exception {
        assertThat(TrackingPhotoPurgeParticipant.class.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(TrackingPhotoPurgeParticipant.class.getMethod("purgeDue", int.class, boolean.class, int.class,
                LocalDateTime.class).isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }
}
