package com.yadony.api.requests.service;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.requests.dto.PackageRequestPhotoResponse;
import com.yadony.api.requests.entity.PackageRequestPhotoEntity;
import com.yadony.api.requests.repository.PackageRequestPhotoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageRequestPhotoServiceTest {

    @Mock private PackageRequestPhotoRepository photoRepository;
    @Mock private StorageService storageService;
    @InjectMocks private PackageRequestPhotoService service;

    @Captor private ArgumentCaptor<List<PackageRequestPhotoEntity>> rowsCaptor;

    private static final UUID REQ = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();

    private String key(String name) {
        return "package_requests/" + SENDER + "/" + name;
    }

    @Test
    void replacePhotos_deletesThenPersistsRowsWithPositions() {
        service.replacePhotos(REQ, SENDER, List.of(key("1.jpg"), key("2.jpg")));

        verify(photoRepository).deleteByPackageRequestId(REQ);
        verify(photoRepository).saveAll(rowsCaptor.capture());
        List<PackageRequestPhotoEntity> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getPosition()).isEqualTo(0);
        assertThat(rows.get(1).getPosition()).isEqualTo(1);
        assertThat(rows.get(0).getPackageRequestId()).isEqualTo(REQ);
    }

    @Test
    void replacePhotos_nullOrEmpty_deletesAndDoesNotSave() {
        service.replacePhotos(REQ, SENDER, null);
        service.replacePhotos(REQ, SENDER, List.of());
        verify(photoRepository, never()).saveAll(any());
        verify(photoRepository, org.mockito.Mockito.times(2)).deleteByPackageRequestId(REQ);
    }

    @Test
    void replacePhotos_tooMany_throws422_andDoesNotDelete() {
        assertThatThrownBy(() -> service.replacePhotos(REQ, SENDER,
                List.of(key("1"), key("2"), key("3"), key("4"), key("5"))))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("too-many-photos");
                });
        verify(photoRepository, never()).deleteByPackageRequestId(any());
        verify(photoRepository, never()).saveAll(any());
    }

    @Test
    void replacePhotos_keyOutsidePrefix_throws422() {
        assertThatThrownBy(() -> service.replacePhotos(REQ, SENDER, List.of("kyc/evil.jpg")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("invalid-photo-key"));
    }

    @Test
    void replacePhotos_keyOfAnotherSender_throws422() {
        String foreign = "package_requests/" + UUID.randomUUID() + "/1.jpg";
        assertThatThrownBy(() -> service.replacePhotos(REQ, SENDER, List.of(foreign)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("invalid-photo-key"));
    }

    // Audit sécu 21/10 : le préfixe du propriétaire ne suffit pas, un segment « .. » en sort.
    @Test
    void replacePhotos_dotSegmentsEscapingOwnerPrefix_throws422() {
        for (String key : List.of("package_requests/" + SENDER + "/../../bids/" + java.util.UUID.randomUUID() + "/1.jpg",
                    "package_requests/" + SENDER + "/./1.jpg",
                    "package_requests/" + SENDER + "//1.jpg",
                    "package_requests/" + SENDER + "/..\\1.jpg",
                    "package_requests/" + SENDER + "/")) {
            assertThatThrownBy(() -> service.replacePhotos(REQ, SENDER, List.of(key)))
                    .as(key)
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-photo-key"));
        }
        verify(photoRepository, never()).saveAll(any());
    }

    @Test
    void objectKeys_returnsOrderedRawKeys() {
        when(photoRepository.findByPackageRequestIdOrderByPositionAsc(REQ))
                .thenReturn(List.of(
                        new PackageRequestPhotoEntity(REQ, key("a.jpg"), 0),
                        new PackageRequestPhotoEntity(REQ, key("b.jpg"), 1)));

        assertThat(service.objectKeys(REQ)).containsExactly(key("a.jpg"), key("b.jpg"));
    }

    @Test
    void activePhotos_mapsToPresignedUrls() {
        when(photoRepository.findByPackageRequestIdOrderByPositionAsc(REQ))
                .thenReturn(List.of(new PackageRequestPhotoEntity(REQ, key("1.jpg"), 0)));
        when(storageService.generatePresignedUrl(eq(key("1.jpg")), any()))
                .thenReturn("https://signed/1");

        List<PackageRequestPhotoResponse> out = service.activePhotos(REQ);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).objectKey()).isEqualTo(key("1.jpg"));
        assertThat(out.get(0).url()).isEqualTo("https://signed/1");
    }

    @Test
    void firstPhotoUrl_returnsPresignedOrNull() {
        when(photoRepository.findFirstByPackageRequestIdOrderByPositionAsc(REQ))
                .thenReturn(Optional.of(new PackageRequestPhotoEntity(REQ, key("1.jpg"), 0)));
        when(storageService.generatePresignedUrl(eq(key("1.jpg")), any())).thenReturn("https://signed/1");
        assertThat(service.firstPhotoUrl(REQ)).isEqualTo("https://signed/1");

        when(photoRepository.findFirstByPackageRequestIdOrderByPositionAsc(REQ))
                .thenReturn(Optional.empty());
        assertThat(service.firstPhotoUrl(REQ)).isNull();
    }
}
