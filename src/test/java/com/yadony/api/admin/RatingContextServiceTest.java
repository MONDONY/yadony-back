package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import com.yadony.api.admin.dto.AdminReportContextResponse.RelatedReport;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RatingContextServiceTest {

    @Mock RatingRepository ratingRepo;
    @Mock UserRepository userRepo;
    @Mock ReportRepository reportRepo;
    @Mock ReportContextService reportContext;

    private RatingContextService service;
    private final UUID ratingId = UUID.randomUUID();
    private final UUID raterId = UUID.randomUUID();
    private final UUID ratedId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RatingContextService(ratingRepo, userRepo, reportRepo, reportContext);
        lenient().when(reportContext.history("RATING", ratingId)).thenReturn(List.of());
        lenient().when(reportContext.toRelated(List.of())).thenReturn(List.of());
    }

    private RatingEntity rating(UUID rater, UUID rated) {
        RatingEntity r = new RatingEntity();
        ReflectionTestUtils.setField(r, "id", ratingId);
        r.setRaterId(rater);
        r.setRatedUserId(rated);
        return r;
    }

    private static UserEntity user(UUID id, String first, String last) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "createdAt", LocalDateTime.of(2026, 3, 1, 9, 0));
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    // ---- notant ----

    @Test
    void notant_avisDonnesMoyenneArrondieEtNotesBasses() {
        when(userRepo.findById(raterId)).thenReturn(Optional.of(user(raterId, "Awa", "Diallo")));
        lenient().when(userRepo.findById(ratedId)).thenReturn(Optional.empty());
        when(ratingRepo.countByRaterId(raterId)).thenReturn(12L);
        when(ratingRepo.averageStarsByRaterId(raterId)).thenReturn(1.8333333);
        when(ratingRepo.countByRaterIdAndStarsLessThanEqual(raterId, RatingContextService.LOW_STARS)).thenReturn(10L);

        var rater = service.build(rating(raterId, ratedId)).rater();

        assertThat(rater.userId()).isEqualTo(raterId);
        assertThat(rater.displayName()).isEqualTo("Awa Diallo");
        assertThat(rater.memberSince()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 0));
        assertThat(rater.ratingsGiven()).isEqualTo(12);
        assertThat(rater.averageGiven()).isEqualByComparingTo("1.8");
        assertThat(rater.lowRatingsGiven()).isEqualTo(10);
    }

    @Test
    void notant_sansAvisEmis_moyenneNulle() {
        when(userRepo.findById(raterId)).thenReturn(Optional.of(user(raterId, "Awa", "Diallo")));
        lenient().when(userRepo.findById(ratedId)).thenReturn(Optional.empty());
        when(ratingRepo.averageStarsByRaterId(raterId)).thenReturn(null);

        assertThat(service.build(rating(raterId, ratedId)).rater().averageGiven()).isNull();
    }

    @Test
    void notantAnonymeOuSupprime_null_sansRequete() {
        lenient().when(userRepo.findById(ratedId)).thenReturn(Optional.empty());
        assertThat(service.build(rating(null, ratedId)).rater()).isNull();

        when(userRepo.findById(raterId)).thenReturn(Optional.empty());
        assertThat(service.build(rating(raterId, ratedId)).rater()).isNull();
        verify(ratingRepo, never()).countByRaterId(raterId);
    }

    // ---- noté ----

    @Test
    void note_moyenneNombreExclusEtRepartitionTrieeParNote() {
        lenient().when(userRepo.findById(raterId)).thenReturn(Optional.empty());
        UserEntity rated = user(ratedId, "Moussa", "Ba");
        rated.setAverageRating(new BigDecimal("4.2"));
        ReflectionTestUtils.setField(rated, "ratingCount", 18);
        when(userRepo.findById(ratedId)).thenReturn(Optional.of(rated));
        when(ratingRepo.countByRatedUserIdAndExcludedFromAverageTrue(ratedId)).thenReturn(2L);
        when(ratingRepo.countIncludedByStars(ratedId)).thenReturn(List.of(new Object[] {5, 12L}, new Object[] {1, 2L}, new Object[] {4, 4L}));

        var r = service.build(rating(raterId, ratedId)).rated();

        assertThat(r.userId()).isEqualTo(ratedId);
        assertThat(r.displayName()).isEqualTo("Moussa Ba");
        assertThat(r.averageRating()).isEqualByComparingTo("4.2");
        assertThat(r.ratingCount()).isEqualTo(18);
        assertThat(r.excludedCount()).isEqualTo(2);
        assertThat(r.distribution()).containsExactly(Map.entry(1, 2L), Map.entry(4, 4L), Map.entry(5, 12L));
    }

    @Test
    void noteSupprime_null() {
        lenient().when(userRepo.findById(raterId)).thenReturn(Optional.empty());
        when(userRepo.findById(ratedId)).thenReturn(Optional.empty());

        assertThat(service.build(rating(raterId, ratedId)).rated()).isNull();
        assertThat(service.build(rating(raterId, null)).rated()).isNull();
    }

    // ---- même relation ----

    @Test
    void memeRelation_notesDuMemeNotantAuMemeNote() {
        lenient().when(userRepo.findById(raterId)).thenReturn(Optional.empty());
        lenient().when(userRepo.findById(ratedId)).thenReturn(Optional.empty());
        when(ratingRepo.countByRaterIdAndRatedUserId(raterId, ratedId)).thenReturn(3L);

        assertThat(service.build(rating(raterId, ratedId)).sameRelationCount()).isEqualTo(3);
        assertThat(service.build(rating(null, ratedId)).sameRelationCount()).isZero();
        assertThat(service.build(rating(raterId, null)).sameRelationCount()).isZero();
    }

    // ---- signalements et historique ----

    @Test
    void signalementsSurLAvis_etHistorique_viennentDuServiceDeContexteDesSignalements() {
        lenient().when(userRepo.findById(raterId)).thenReturn(Optional.empty());
        lenient().when(userRepo.findById(ratedId)).thenReturn(Optional.empty());
        ReportEntity report = new ReportEntity();
        List<ReportEntity> reports = List.of(report);
        var related = List.of(new RelatedReport(UUID.randomUUID(), "INAPPROPRIATE_CONTENT", "OPEN", null, "Fatou Kane"));
        var history = List.of(new HistoryEntry("RATING_EXCLUDED", "REINTEGRATED", null, "sira@yadony.com", null));
        when(reportRepo.findTop10ByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType.RATING, ratingId)).thenReturn(reports);
        when(reportContext.toRelated(reports)).thenReturn(related);
        when(reportRepo.countByTargetTypeAndTargetIdAndStatus(ReportTargetType.RATING, ratingId, ReportStatus.OPEN)).thenReturn(1L);
        when(reportContext.history("RATING", ratingId)).thenReturn(history);

        var ctx = service.build(rating(raterId, ratedId));

        assertThat(ctx.reports()).isSameAs(related);
        assertThat(ctx.openReports()).isEqualTo(1);
        assertThat(ctx.history()).isSameAs(history);
    }

    @Test
    void avisSansIdentifiant_aucuneLectureDeSignalementsNiDAudit() {
        RatingEntity noId = new RatingEntity();
        noId.setRatedUserId(ratedId);
        lenient().when(userRepo.findById(ratedId)).thenReturn(Optional.empty());
        when(reportContext.history("RATING", null)).thenReturn(List.of());

        var ctx = service.build(noId);

        assertThat(ctx.reports()).isEmpty();
        assertThat(ctx.openReports()).isZero();
        verify(reportRepo, never()).findTop10ByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType.RATING, null);
    }
}
