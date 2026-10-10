package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.matching.dto.BidPhotoResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidPhotoServiceTest {

    @Mock private BidPhotoRepository photoRepository;
    @Mock private StorageService storageService;
    @Mock private MultipartFile file;
    @InjectMocks private BidPhotoService service;

    @Captor private ArgumentCaptor<List<BidPhotoEntity>> rowsCaptor;

    private static final UUID BID = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();

    @Test
    void attachPhotos_persistsActiveRowsWithPositions() {
        service.attachPhotos(BID, SENDER,
                List.of("bids/" + SENDER + "/1.jpg", "bids/" + SENDER + "/2.jpg"));

        verify(photoRepository).saveAll(rowsCaptor.capture());
        List<BidPhotoEntity> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getPosition()).isEqualTo(0);
        assertThat(rows.get(1).getPosition()).isEqualTo(1);
        assertThat(rows).allMatch(r -> r.getStatus() == BidPhotoStatus.ACTIVE);
    }

    @Test
    void attachPhotos_nullOrEmpty_doesNothing() {
        service.attachPhotos(BID, SENDER, null);
        service.attachPhotos(BID, SENDER, List.of());
        verify(photoRepository, never()).saveAll(any());
    }

    @Test
    void attachPhotos_tooMany_throws422() {
        assertThatThrownBy(() -> service.attachPhotos(BID, SENDER,
                List.of("bids/1", "bids/2", "bids/3", "bids/4", "bids/5")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("too-many-photos");
                });
    }

    @Test
    void uploadPhoto_returnsKeyFromStorage() throws IOException {
        UUID sender = UUID.randomUUID();
        when(storageService.storeImage(eq(file), any(StorageService.MediaUpload.class)))
                .thenReturn("bids/" + sender + "/1.jpg");

        assertThat(service.uploadPhoto(sender, file)).isEqualTo("bids/" + sender + "/1.jpg");

        ArgumentCaptor<StorageService.MediaUpload> captor = ArgumentCaptor.forClass(StorageService.MediaUpload.class);
        verify(storageService).storeImage(eq(file), captor.capture());
        assertThat(captor.getValue().prefix()).isEqualTo("bids/" + sender + "/");
        assertThat(captor.getValue().ownerId()).isEqualTo(sender);
        assertThat(captor.getValue().purpose()).isEqualTo(MediaPurpose.BID);
        assertThat(captor.getValue().quotaExempt()).isFalse();
    }

    @Test
    void uploadPhoto_quotaExceeded_propagates429() {
        UUID sender = UUID.randomUUID();
        when(storageService.storeImage(any(), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.TOO_MANY_REQUESTS, "photo-upload-quota-exceeded", "Too Many Requests", "max"));

        assertThatThrownBy(() -> service.uploadPhoto(sender, file))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void attachPhotos_keyOutsideBidsPrefix_throws422() {
        assertThatThrownBy(() -> service.attachPhotos(BID, SENDER, List.of("kyc/evil.jpg")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("invalid-photo-key"));
    }

    @Test
    void attachPhotos_keyOfAnotherSender_throws422() {
        // Clé valide côté préfixe bids/ mais appartenant à un autre expéditeur :
        // doit être refusée, sinon une clé volée donne accès à la photo via URL présignée.
        assertThatThrownBy(() -> service.attachPhotos(BID, SENDER,
                List.of("bids/" + UUID.randomUUID() + "/1.jpg")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("invalid-photo-key"));
    }

    @Test
    void activePhotos_mapsToPresignedUrls() {
        BidPhotoEntity row = new BidPhotoEntity(BID, "bids/s/1.jpg", 0);
        when(photoRepository.findByBidIdAndStatusOrderByPositionAsc(BID, BidPhotoStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(storageService.generatePresignedUrl(eq("bids/s/1.jpg"), any()))
                .thenReturn("https://signed/1");

        List<BidPhotoResponse> out = service.activePhotos(BID);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).url()).isEqualTo("https://signed/1");
    }

    @Test
    void markDeletingForBid_flipsActiveRows() {
        BidPhotoEntity row = new BidPhotoEntity(BID, "bids/s/1.jpg", 0);
        when(photoRepository.findByBidIdAndStatusOrderByPositionAsc(BID, BidPhotoStatus.ACTIVE))
                .thenReturn(List.of(row));

        service.markDeletingForBid(BID);

        assertThat(row.getStatus()).isEqualTo(BidPhotoStatus.DELETING);
        verify(photoRepository).saveAll(any());
    }
}
