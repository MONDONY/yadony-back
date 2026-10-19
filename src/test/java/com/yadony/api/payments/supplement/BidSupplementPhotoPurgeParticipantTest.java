package com.yadony.api.payments.supplement;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Photo du supplément : purgée avec les mêmes règles que les photos de suivi (N jours après la livraison). */
@ExtendWith(MockitoExtension.class)
class BidSupplementPhotoPurgeParticipantTest {

    @Mock BidSupplementRepository supplements;
    @Mock BidRepository bids;
    @Mock StorageService storage;
    @Mock AuditService audit;
    @Mock MessagingMediaRetentionHold hold;

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 10, 0);
    final UUID bidId = UUID.randomUUID();
    final RecordingTransactionManager txm = new RecordingTransactionManager();
    BidSupplementPhotoPurgeParticipant participant;

    @BeforeEach
    void setUp() {
        participant = new BidSupplementPhotoPurgeParticipant(supplements, bids, storage, audit, List.of(hold), txm);
        lenient().when(supplements.findBidIdsWithLivePhotos()).thenReturn(List.of(bidId));
    }

    private BidEntity completedDaysAgo(int days) {
        BidEntity b = new BidEntity();
        b.setStatus(BidStatus.COMPLETED);
        b.markDelivered(NOW.minusDays(days));
        return b;
    }

    private BidSupplementEntity supplement() {
        BidSupplementEntity s = new BidSupplementEntity();
        s.setBidId(bidId);
        s.setPhotoKey("tracking/" + bidId + "/1_supplement.jpg");
        when(supplements.findLivePhotosByBidId(bidId)).thenReturn(List.of(s));
        return s;
    }

    @Test
    void livreIlYALongtemps_supprimeEtMarque() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        BidSupplementEntity s = supplement();

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        verify(storage).deleteFile("tracking/" + bidId + "/1_supplement.jpg");
        assertThat(s.getPhotoPurgedAt()).isEqualTo(NOW);
        assertThat(o).isEqualTo(new PhotoPurgeOutcome(1, 1, 0));
        verify(supplements).save(s);
        verify(audit).log(eq("bid"), eq(bidId), eq("BID_SUPPLEMENT_PHOTOS_PURGED"), isNull(), anyMap());
    }

    @Test
    void objetDejaAbsent_compteCommeSupprime() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        BidSupplementEntity s = supplement();
        doThrow(NoSuchKeyException.builder().build()).when(storage).deleteFile(any());

        assertThat(participant.purgeDue(7, false, 500, NOW).deleted()).isEqualTo(1);
        assertThat(s.getPhotoPurgedAt()).isEqualTo(NOW);
    }

    @Test
    void echecR2_nonMarque() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        BidSupplementEntity s = supplement();
        doThrow(new IllegalStateException("R2")).when(storage).deleteFile(any());

        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(new PhotoPurgeOutcome(1, 0, 1));
        assertThat(s.getPhotoPurgedAt()).isNull();
        verify(supplements, never()).save(any());
    }

    @Test
    void colisEnCoursRecentOuRetenu_conserve() {
        BidEntity running = new BidEntity();
        running.setStatus(BidStatus.IN_TRANSIT);
        when(bids.findById(bidId)).thenReturn(Optional.of(running));
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);

        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(3)));
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);

        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        when(hold.holds(eq(bidId), any())).thenReturn(true);
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);

        when(bids.findById(bidId)).thenReturn(Optional.empty());
        assertThat(participant.purgeDue(7, false, 500, NOW)).isEqualTo(PhotoPurgeOutcome.NONE);
        verify(storage, never()).deleteFile(any());
    }

    @Test
    void aBlanc_compteSansSupprimer() {
        when(bids.findById(bidId)).thenReturn(Optional.of(completedDaysAgo(8)));
        BidSupplementEntity s = supplement();

        assertThat(participant.purgeDue(7, true, 500, NOW)).isEqualTo(new PhotoPurgeOutcome(1, 0, 0));
        verify(storage, never()).deleteFile(any());
        assertThat(s.getPhotoPurgedAt()).isNull();
        assertThat(participant.name()).isEqualTo("bid-supplement");
    }
}
