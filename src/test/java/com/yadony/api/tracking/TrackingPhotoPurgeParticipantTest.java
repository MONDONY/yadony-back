package com.yadony.api.tracking;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
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
    TrackingPhotoPurgeParticipant participant;

    @BeforeEach
    void setUp() {
        participant = new TrackingPhotoPurgeParticipant(events, bids, storage, audit, List.of(hold));
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
}
