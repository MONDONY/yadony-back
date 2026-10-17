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
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Motifs pour lesquels un titulaire GP Pro doit être réexaminé. */
@Component
public class GpProReviewEvaluator {

    static final int RATINGS_WINDOW = 5;
    static final double LOW_RATING_THRESHOLD = 4.0;
    static final int INACTIVE_DAYS = 56;

    private final RatingRepository ratings;
    private final DisputeRepository disputes;
    private final ReportRepository reports;
    private final AnnouncementRepository announcements;

    public GpProReviewEvaluator(RatingRepository ratings, DisputeRepository disputes,
                                ReportRepository reports, AnnouncementRepository announcements) {
        this.ratings = ratings;
        this.disputes = disputes;
        this.reports = reports;
        this.announcements = announcements;
    }

    public Set<GpProReviewReason> reasonsFor(UserEntity user, LocalDate today) {
        Set<GpProReviewReason> reasons = EnumSet.noneOf(GpProReviewReason.class);
        if (isLowRating(user)) {
            reasons.add(GpProReviewReason.LOW_RATING);
        }
        if (disputes.existsByTravelerIdAndStatus(user.getId(), "OPEN")) {
            reasons.add(GpProReviewReason.OPEN_DISPUTE);
        }
        if (reports.existsByTargetTypeAndTargetIdAndStatus(ReportTargetType.USER, user.getId(), ReportStatus.OPEN)) {
            reasons.add(GpProReviewReason.OPEN_REPORT);
        }
        if (isInactive(user, today)) {
            reasons.add(GpProReviewReason.INACTIVE);
        }
        if (user.getKycStatus() != KycStatus.VERIFIED) {
            reasons.add(GpProReviewReason.KYC_NOT_VERIFIED);
        }
        return reasons;
    }

    private boolean isLowRating(UserEntity user) {
        List<RatingEntity> recent = ratings.findRecentIncludedRatings(user.getId());
        if (recent.size() < RATINGS_WINDOW) {
            return false;
        }
        double average = recent.stream().limit(RATINGS_WINDOW).mapToInt(RatingEntity::getStars).average().orElse(5);
        return average < LOW_RATING_THRESHOLD;
    }

    /** Dernière activité = la plus récente entre la dernière annonce publiée et l'attribution du badge. */
    private boolean isInactive(UserEntity user, LocalDate today) {
        LocalDateTime last = announcements.findLastNonDraftCreatedAt(user.getId());
        LocalDateTime granted = user.getGpProGrantedAt();
        if (last == null || (granted != null && granted.isAfter(last))) {
            last = granted;
        }
        return last != null && last.toLocalDate().isBefore(today.minusDays(INACTIVE_DAYS));
    }
}
