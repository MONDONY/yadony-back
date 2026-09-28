package com.yadony.api.admin;

import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requetes natives de la restauration admin, sur un vrai PostgreSQL : elles contournent le
 * {@code @Where(deleted_at IS NULL)} des entites, ce qu'aucun mock ne prouve.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class AdminRestorationRepositoryIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired RatingRepository ratingRepository;
    @Autowired ReportRepository reportRepository;
    @Autowired AuditService auditService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManager em;
    @Autowired com.yadony.api.ratings.RatingService ratingService;
    @Autowired com.yadony.api.auth.UserRepository userRepository;

    private RatingEntity rating(int stars, boolean flagged, boolean deleted) {
        RatingEntity r = new RatingEntity();
        r.setRaterId(UUID.randomUUID());
        r.setRatedUserId(UUID.randomUUID());
        r.setBidId(UUID.randomUUID());
        r.setStars(stars);
        r.setFlagged(flagged);
        if (deleted) {
            r.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        return ratingRepository.saveAndFlush(r);
    }

    private ReportEntity report(ReportStatus status, boolean deleted) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(ReportTargetType.USER);
        r.setTargetId(UUID.randomUUID());
        r.setReporterId(UUID.randomUUID());
        r.setReason(ReportReason.values()[0]);
        r.setDescription("colis jamais livre");
        r.setStatus(status);
        if (deleted) {
            r.softDelete();
        }
        return reportRepository.saveAndFlush(r);
    }

    @Test
    void deletedRatings_areListedOnlyByTheDeletedQuery_withFilters() {
        RatingEntity visible = rating(5, false, false);
        RatingEntity deletedFlagged = rating(1, true, true);
        RatingEntity deletedHigh = rating(5, false, true);
        em.clear();

        var deleted = ratingRepository.findDeletedAdminFiltered(null, null, null, PageRequest.of(0, 50));
        assertThat(deleted.getContent()).extracting(RatingEntity::getId)
                .contains(deletedFlagged.getId(), deletedHigh.getId())
                .doesNotContain(visible.getId());
        assertThat(deleted.getContent()).allMatch(r -> r.getDeletedAt() != null);

        var flaggedOnly = ratingRepository.findDeletedAdminFiltered(true, null, null, PageRequest.of(0, 50));
        assertThat(flaggedOnly.getContent()).extracting(RatingEntity::getId)
                .contains(deletedFlagged.getId()).doesNotContain(deletedHigh.getId());

        var lowScores = ratingRepository.findDeletedAdminFiltered(null, 1, 2, PageRequest.of(0, 50));
        assertThat(lowScores.getContent()).extracting(RatingEntity::getId)
                .contains(deletedFlagged.getId()).doesNotContain(deletedHigh.getId());

        var active = ratingRepository.findAdminFiltered(null, null, null, PageRequest.of(0, 50));
        assertThat(active.getContent()).extracting(RatingEntity::getId)
                .contains(visible.getId()).doesNotContain(deletedFlagged.getId());
    }

    @Test
    void deletedRating_isFoundIncludingDeleted_andRestoredBySave() {
        RatingEntity deleted = rating(4, false, true);
        em.clear();

        assertThat(ratingRepository.findById(deleted.getId())).isEmpty();
        RatingEntity loaded = ratingRepository.findByIdIncludingDeleted(deleted.getId()).orElseThrow();
        assertThat(loaded.getDeletedAt()).isNotNull();

        loaded.setDeletedAt(null);
        ratingRepository.saveAndFlush(loaded);
        em.clear();

        assertThat(ratingRepository.findById(deleted.getId())).isPresent();
    }

    @Test
    void deletedReports_areListedAndFoundIncludingDeleted_andRestoredBySave() {
        ReportEntity visible = report(ReportStatus.OPEN, false);
        ReportEntity deletedOpen = report(ReportStatus.OPEN, true);
        ReportEntity deletedResolved = report(ReportStatus.RESOLVED, true);
        em.clear();

        var all = reportRepository.findDeletedFiltered(null, null, null, PageRequest.of(0, 50));
        assertThat(all.getContent()).extracting(ReportEntity::getId)
                .contains(deletedOpen.getId(), deletedResolved.getId())
                .doesNotContain(visible.getId());

        var open = reportRepository.findDeletedFiltered(ReportStatus.OPEN.name(), ReportTargetType.USER.name(),
                null, PageRequest.of(0, 50));
        assertThat(open.getContent()).extracting(ReportEntity::getId)
                .contains(deletedOpen.getId()).doesNotContain(deletedResolved.getId());

        var searched = reportRepository.findDeletedFiltered(null, null, "%jamais%", PageRequest.of(0, 50));
        assertThat(searched.getContent()).extracting(ReportEntity::getId).contains(deletedOpen.getId());
        var missed = reportRepository.findDeletedFiltered(null, null, "%introuvable-xyz%", PageRequest.of(0, 50));
        assertThat(missed.getContent()).isEmpty();

        List<ReportEntity> byIds = reportRepository.findAllByIdIncludingDeleted(
                List.of(visible.getId(), deletedOpen.getId()));
        assertThat(byIds).extracting(ReportEntity::getId)
                .containsExactlyInAnyOrder(visible.getId(), deletedOpen.getId());

        ReportEntity toRestore = byIds.stream().filter(r -> r.getId().equals(deletedOpen.getId())).findFirst().orElseThrow();
        toRestore.setDeletedAt(null);
        reportRepository.saveAndFlush(toRestore);
        em.clear();
        assertThat(reportRepository.findById(deletedOpen.getId())).isPresent();
    }

    @Test
    void auditLookup_returnsDeletionTracesNewestFirst() {
        UUID entity = UUID.randomUUID();
        UUID firstAdmin = UUID.randomUUID();
        UUID secondAdmin = UUID.randomUUID();
        auditService.log("RATING", entity, "RATING_DELETED", firstAdmin, Map.of("reason", "premier"));
        auditService.log("RATING", entity, "RATING_DELETED", secondAdmin, Map.of("reason", "second"));
        auditService.log("RATING", entity, "RATING_EXCLUDED", firstAdmin, Map.of());

        var traces = auditLogRepository.findByEntityTypeAndActionAndEntityIdInOrderByCreatedAtDescIdDesc(
                "RATING", "RATING_DELETED", List.of(entity));

        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).getActorId()).isEqualTo(secondAdmin);
    }

    @Test
    void deletingThenRestoringARating_movesTheTravelerAverageBothWays() {
        com.yadony.api.auth.UserEntity traveler = new com.yadony.api.auth.UserEntity();
        traveler.setFirebaseUid("restore-avg-" + UUID.randomUUID());
        traveler.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        traveler.setKycStatus(com.yadony.api.auth.KycStatus.PENDING);
        traveler.setRoles(java.util.Set.of(com.yadony.api.auth.Role.TRAVELER));
        traveler.setStripeAccountStatus(com.yadony.api.auth.StripeAccountStatus.NOT_CREATED);
        UUID travelerId = userRepository.saveAndFlush(traveler).getId();

        RatingEntity five = rating(5, false, false);
        five.setRatedUserId(travelerId);
        ratingRepository.saveAndFlush(five);
        RatingEntity one = rating(1, false, false);
        one.setRatedUserId(travelerId);
        ratingRepository.saveAndFlush(one);
        ratingService.recalculateAverageRating(travelerId);
        assertThat(userRepository.findById(travelerId).orElseThrow().getAverageRating())
                .isEqualByComparingTo("3.00");

        // Suppression : meme sequence que le controleur (flush puis recalcul).
        one.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        ratingRepository.saveAndFlush(one);
        ratingService.recalculateAverageRating(travelerId);
        var afterDelete = userRepository.findById(travelerId).orElseThrow();
        assertThat(afterDelete.getAverageRating()).isEqualByComparingTo("5.00");
        assertThat(afterDelete.getRatingCount()).isEqualTo(1);

        em.clear();
        RatingEntity restored = ratingRepository.findByIdIncludingDeleted(one.getId()).orElseThrow();
        restored.setDeletedAt(null);
        ratingRepository.saveAndFlush(restored);
        ratingService.recalculateAverageRating(travelerId);
        var afterRestore = userRepository.findById(travelerId).orElseThrow();
        assertThat(afterRestore.getAverageRating()).isEqualByComparingTo("3.00");
        assertThat(afterRestore.getRatingCount()).isEqualTo(2);
    }
}
