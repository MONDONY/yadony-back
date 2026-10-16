package com.yadony.api.signalements;

import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.PhotoPurgeOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Régression du 16/10 (leak detection Hikari sur scheduling-2) : à travers le vrai bean Spring
 * (proxy compris), aucune transaction n'est ouverte pendant la suppression R2, et la ligne est
 * bien marquée (soft delete) après coup.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportPhotoPurgeTransactionIT {

    @Autowired ReportPhotoPurgeParticipant participant;
    @Autowired ReportRepository reports;
    @Autowired ReportPhotoRepository photos;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StorageService storage;

    @Test
    void suppressionR2_horsTransaction_puisSoftDelete() {
        // Clôture très ancienne : la photo est due quel que soit le jeu de données partagé.
        OffsetDateTime resolvedAt = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ReportEntity r = new ReportEntity();
        r.setTargetType(ReportTargetType.USER);
        r.setTargetId(UUID.randomUUID());
        r.setReporterId(UUID.randomUUID());
        r.setReason(ReportReason.values()[0]);
        r.setStatus(ReportStatus.RESOLVED);
        r.setResolvedAt(resolvedAt);
        reports.saveAndFlush(r);
        String key = "reports/it/" + UUID.randomUUID() + ".jpg";
        ReportPhotoEntity p = new ReportPhotoEntity();
        p.setReportId(r.getId());
        p.setObjectKey(key);
        photos.saveAndFlush(p);

        Map<String, Boolean> txPendantR2 = new ConcurrentHashMap<>();
        doAnswer(inv -> txPendantR2.put(inv.getArgument(0),
                TransactionSynchronizationManager.isActualTransactionActive()))
                .when(storage).deleteFile(anyString());

        PhotoPurgeOutcome o = participant.purgeDue(7, false, 500, LocalDateTime.of(2026, 10, 16, 8, 40));

        assertThat(txPendantR2).containsEntry(key, false);
        assertThat(txPendantR2.values()).doesNotContain(true);
        assertThat(o.deleted()).isGreaterThanOrEqualTo(1);
        // Lecture brute : l'entité soft-deleted est filtrée par le repository.
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM report_photos WHERE id = ?",
                java.sql.Timestamp.class, p.getId())).isNotNull();
    }
}
