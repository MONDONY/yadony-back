package com.yadony.api.gppro;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GpProReviewEvaluatorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @Mock RatingRepository ratings;
    @Mock DisputeRepository disputes;
    @Mock ReportRepository reports;
    @Mock AnnouncementRepository announcements;

    GpProReviewEvaluator evaluator;
    UserEntity user;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        evaluator = new GpProReviewEvaluator(ratings, disputes, reports, announcements);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setGpProGrantedAt(TODAY.minusDays(10).atStartOfDay());
        when(announcements.findLastNonDraftCreatedAt(userId)).thenReturn(TODAY.minusDays(1).atStartOfDay());
    }

    private void givenStars(int... stars) {
        List<RatingEntity> list = new ArrayList<>();
        for (int s : stars) {
            RatingEntity r = new RatingEntity();
            r.setStars(s);
            list.add(r);
        }
        when(ratings.findRecentIncludedRatings(userId)).thenReturn(list);
    }

    @Test
    void healthyAccountHasNoReason() {
        assertThat(evaluator.reasonsFor(user, TODAY)).isEmpty();
    }

    @Test
    void fourRatingsNeverLowRating() {
        givenStars(1, 1, 1, 1);
        assertThat(evaluator.reasonsFor(user, TODAY)).doesNotContain(GpProReviewReason.LOW_RATING);
    }

    @Test
    void averageExactly4IsNotLow() {
        givenStars(4, 4, 4, 4, 4);
        assertThat(evaluator.reasonsFor(user, TODAY)).doesNotContain(GpProReviewReason.LOW_RATING);
    }

    @Test
    void average3_8IsLow() {
        givenStars(4, 4, 4, 4, 3, 1, 1);
        // seules les 5 premières comptent : 4,4,4,4,3 = 3.8
        assertThat(evaluator.reasonsFor(user, TODAY)).contains(GpProReviewReason.LOW_RATING);
    }

    @Test
    void ratingsBeyondTheFifthAreIgnored() {
        givenStars(5, 5, 5, 5, 5, 1, 1, 1);
        assertThat(evaluator.reasonsFor(user, TODAY)).doesNotContain(GpProReviewReason.LOW_RATING);
    }

    @Test
    void inactiveAfter56Days() {
        when(announcements.findLastNonDraftCreatedAt(userId)).thenReturn(TODAY.minusDays(57).atStartOfDay());
        user.setGpProGrantedAt(TODAY.minusDays(100).atStartOfDay());
        assertThat(evaluator.reasonsFor(user, TODAY)).contains(GpProReviewReason.INACTIVE);
    }

    @Test
    void notInactiveAt55Days() {
        when(announcements.findLastNonDraftCreatedAt(userId)).thenReturn(TODAY.minusDays(55).atStartOfDay());
        user.setGpProGrantedAt(TODAY.minusDays(100).atStartOfDay());
        assertThat(evaluator.reasonsFor(user, TODAY)).doesNotContain(GpProReviewReason.INACTIVE);
    }

    @Test
    void exactly56DaysIsNotYetInactive() {
        when(announcements.findLastNonDraftCreatedAt(userId)).thenReturn(TODAY.minusDays(56).atTime(23, 0));
        user.setGpProGrantedAt(TODAY.minusDays(100).atStartOfDay());
        assertThat(evaluator.reasonsFor(user, TODAY)).doesNotContain(GpProReviewReason.INACTIVE);
    }

    @Test
    void recentGrantResetsInactivity() {
        when(announcements.findLastNonDraftCreatedAt(userId)).thenReturn(TODAY.minusDays(200).atStartOfDay());
        user.setGpProGrantedAt(TODAY.minusDays(3).atStartOfDay());
        assertThat(evaluator.reasonsFor(user, TODAY)).doesNotContain(GpProReviewReason.INACTIVE);
    }

    @Test
    void noAnnouncementFallsBackOnGrantDate() {
        when(announcements.findLastNonDraftCreatedAt(userId)).thenReturn(null);
        user.setGpProGrantedAt(TODAY.minusDays(60).atStartOfDay());
        assertThat(evaluator.reasonsFor(user, TODAY)).contains(GpProReviewReason.INACTIVE);
    }

    @Test
    void openDisputeAsTravelerFlags() {
        when(disputes.existsByTravelerIdAndStatus(userId, "OPEN")).thenReturn(true);
        assertThat(evaluator.reasonsFor(user, TODAY)).containsExactly(GpProReviewReason.OPEN_DISPUTE);
    }

    @Test
    void openDisputeAsSenderIgnored() {
        // Un litige ouvert où la personne est expéditrice ne correspond à aucun travelerId = user.
        when(disputes.existsByTravelerIdAndStatus(userId, "OPEN")).thenReturn(false);
        assertThat(evaluator.reasonsFor(user, TODAY)).doesNotContain(GpProReviewReason.OPEN_DISPUTE);
    }

    @Test
    void openReportFlags() {
        when(reports.existsByTargetTypeAndTargetIdAndStatus(ReportTargetType.USER, userId, ReportStatus.OPEN))
                .thenReturn(true);
        assertThat(evaluator.reasonsFor(user, TODAY)).containsExactly(GpProReviewReason.OPEN_REPORT);
    }

    @Test
    void kycPendingFlags() {
        user.setKycStatus(KycStatus.PENDING);
        assertThat(evaluator.reasonsFor(user, TODAY)).containsExactly(GpProReviewReason.KYC_NOT_VERIFIED);
    }
}
