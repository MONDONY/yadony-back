package com.yadony.api.signalements;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportPhotoPurgeParticipantTest {

    @Mock ReportPhotoRepository photos;
    @Mock StorageService storage;
    @Mock AuditService audit;
    ReportPhotoPurgeParticipant participant;
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 10, 0);

    @BeforeEach
    void setUp() { participant = new ReportPhotoPurgeParticipant(photos, storage, audit); }

    private ReportPhotoEntity photo(String key) {
        ReportPhotoEntity p = new ReportPhotoEntity();
        p.setReportId(UUID.randomUUID());
        p.setObjectKey(key);
        return p;
    }

    private void due(ReportPhotoEntity... ps) {
        when(photos.findLiveOfClosedReportsBefore(eq(NOW.minusDays(7).atOffset(ZoneOffset.UTC)), any()))
                .thenReturn(List.of(ps));
    }

    @Test
    void nom_du_participant() {
        assertThat(participant.name()).isEqualTo("reports");
    }

    @Test
    void closedLongAgo_deletesAndSoftDeletes() {
        ReportPhotoEntity p = photo("reports/u/1.jpg");
        due(p);

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        verify(storage).deleteFile("reports/u/1.jpg");
        assertThat(p.getDeletedAt()).isNotNull();
        assertThat(o).isEqualTo(new PhotoPurgeOutcome(1, 1, 0));
        verify(photos).save(p);
        verify(audit).log("report_photo", null, "REPORT_PHOTOS_PURGED", null,
                Map.of("count", 1, "reason", "RETENTION_EXPIRED"));
    }

    @Test
    void dryRun_countsOnly() {
        ReportPhotoEntity p = photo("reports/u/1.jpg");
        when(photos.findLiveOfClosedReportsBefore(any(), any())).thenReturn(List.of(p));
        assertThat(participant.purgeDue(7, true, 500, NOW)).isEqualTo(new PhotoPurgeOutcome(1, 0, 0));
        verifyNoInteractions(storage);
        verifyNoInteractions(audit);
        assertThat(p.getDeletedAt()).isNull();
    }

    @Test
    void objetDejaAbsent_estMarqueSupprime() {
        ReportPhotoEntity p = photo("reports/u/1.jpg");
        due(p);
        doThrow(NoSuchKeyException.builder().build()).when(storage).deleteFile("reports/u/1.jpg");

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        assertThat(p.getDeletedAt()).isNotNull();
        assertThat(o).isEqualTo(new PhotoPurgeOutcome(1, 1, 0));
    }

    @Test
    void echecR2_laisseLaLigneIntacte_etPasDAudit() {
        ReportPhotoEntity p = photo("reports/u/1.jpg");
        due(p);
        doThrow(new RuntimeException("R2 down")).when(storage).deleteFile("reports/u/1.jpg");

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        assertThat(p.getDeletedAt()).isNull();
        assertThat(o).isEqualTo(new PhotoPurgeOutcome(1, 0, 1));
        verify(photos, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void echecPartiel_continueSurLesAutresPhotos() {
        ReportPhotoEntity bad = photo("reports/u/bad.jpg");
        ReportPhotoEntity ok = photo("reports/u/ok.jpg");
        due(bad, ok);
        doThrow(new RuntimeException("R2")).when(storage).deleteFile("reports/u/bad.jpg");

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, NOW);

        assertThat(o).isEqualTo(new PhotoPurgeOutcome(2, 1, 1));
        assertThat(ok.getDeletedAt()).isNotNull();
        assertThat(bad.getDeletedAt()).isNull();
    }
}
