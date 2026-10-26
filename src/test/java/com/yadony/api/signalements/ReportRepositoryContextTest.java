package com.yadony.api.signalements;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Requêtes qui alimentent le contexte d'un signalement : fiche du signalant, autres signalements sur la cible. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReportRepositoryContextTest {

    @Autowired ReportRepository reportRepository;
    @Autowired TestEntityManager em;

    private ReportEntity report(ReportTargetType type, UUID targetId, UUID reporterId, ReportStatus status) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(type);
        r.setTargetId(targetId);
        r.setReporterId(reporterId);
        r.setReason(ReportReason.OTHER);
        r.setStatus(status);
        return em.persistAndFlush(r);
    }

    /** @PrePersist date tout au même instant : on fixe la date pour que l'ordre soit déterministe. */
    private void createdAt(ReportEntity r, LocalDateTime at) {
        em.getEntityManager().createNativeQuery("UPDATE reports SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, at).setParameter(2, r.getId()).executeUpdate();
        em.clear();
    }

    @Test
    @DisplayName("les signalements d'un compte sont comptés par statut, sans mélanger les signalants")
    void countsByReporterAndStatus() {
        UUID reporter = UUID.randomUUID();
        report(ReportTargetType.USER, UUID.randomUUID(), reporter, ReportStatus.RESOLVED);
        report(ReportTargetType.USER, UUID.randomUUID(), reporter, ReportStatus.RESOLVED);
        report(ReportTargetType.USER, UUID.randomUUID(), reporter, ReportStatus.DISMISSED);
        report(ReportTargetType.USER, UUID.randomUUID(), reporter, ReportStatus.OPEN);
        report(ReportTargetType.USER, UUID.randomUUID(), UUID.randomUUID(), ReportStatus.RESOLVED);

        assertThat(reportRepository.countByReporterId(reporter)).isEqualTo(4);
        assertThat(reportRepository.countByReporterIdAndStatus(reporter, ReportStatus.RESOLVED)).isEqualTo(2);
        assertThat(reportRepository.countByReporterIdAndStatus(reporter, ReportStatus.DISMISSED)).isEqualTo(1);
    }

    @Test
    @DisplayName("un signalement supprimé n'est pas compté")
    void deletedReportsAreNotCounted() {
        UUID reporter = UUID.randomUUID();
        report(ReportTargetType.USER, UUID.randomUUID(), reporter, ReportStatus.RESOLVED);
        ReportEntity gone = report(ReportTargetType.USER, UUID.randomUUID(), reporter, ReportStatus.RESOLVED);
        gone.softDelete();
        em.persistAndFlush(gone);
        em.clear();

        assertThat(reportRepository.countByReporterIdAndStatus(reporter, ReportStatus.RESOLVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("autres signalements sur la cible : le dossier en cours exclu, le plus récent d'abord")
    void relatedReportsExcludeCurrentAndSortNewestFirst() {
        UUID target = UUID.randomUUID();
        ReportEntity current = report(ReportTargetType.ANNOUNCEMENT, target, UUID.randomUUID(), ReportStatus.OPEN);
        ReportEntity older = report(ReportTargetType.ANNOUNCEMENT, target, UUID.randomUUID(), ReportStatus.RESOLVED);
        ReportEntity newer = report(ReportTargetType.ANNOUNCEMENT, target, UUID.randomUUID(), ReportStatus.OPEN);
        createdAt(older, LocalDateTime.of(2026, 10, 1, 9, 0));
        createdAt(newer, LocalDateTime.of(2026, 10, 20, 9, 0));

        List<ReportEntity> related = reportRepository
                .findTop10ByTargetTypeAndTargetIdAndIdNotOrderByCreatedAtDesc(
                        ReportTargetType.ANNOUNCEMENT, target, current.getId());

        assertThat(related).extracting(ReportEntity::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("autres signalements : même cible seulement, au plus dix, supprimés exclus")
    void relatedReportsAreScopedCappedAndSkipDeleted() {
        UUID target = UUID.randomUUID();
        ReportEntity current = report(ReportTargetType.USER, target, UUID.randomUUID(), ReportStatus.OPEN);
        for (int i = 0; i < 12; i++) {
            report(ReportTargetType.USER, target, UUID.randomUUID(), ReportStatus.OPEN);
        }
        report(ReportTargetType.ANNOUNCEMENT, target, UUID.randomUUID(), ReportStatus.OPEN); // autre type, même identifiant
        report(ReportTargetType.USER, UUID.randomUUID(), UUID.randomUUID(), ReportStatus.OPEN); // autre cible
        ReportEntity gone = report(ReportTargetType.USER, target, UUID.randomUUID(), ReportStatus.OPEN);
        gone.softDelete();
        em.persistAndFlush(gone);
        em.clear();

        List<ReportEntity> related = reportRepository
                .findTop10ByTargetTypeAndTargetIdAndIdNotOrderByCreatedAtDesc(
                        ReportTargetType.USER, target, current.getId());

        assertThat(related).hasSize(10)
                .extracting(ReportEntity::getId).doesNotContain(current.getId(), gone.getId());
    }
}
