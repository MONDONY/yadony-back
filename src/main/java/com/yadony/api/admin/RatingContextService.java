package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminRatingContextResponse;
import com.yadony.api.admin.dto.AdminRatingContextResponse.Rated;
import com.yadony.api.admin.dto.AdminRatingContextResponse.Rater;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Contexte d'un avis pour son dossier : le notant et ce qu'il note d'habitude, la personne notée et
 * ce que l'avis pèse dans sa moyenne, les signalements et les gestes déjà faits. Lecture seule ;
 * chaque morceau est facultatif.
 */
@Service
public class RatingContextService {

    /** « Note basse » : deux étoiles ou moins. */
    static final int LOW_STARS = 2;

    private final RatingRepository ratingRepo;
    private final UserRepository userRepo;
    private final ReportRepository reportRepo;
    private final ReportContextService reportContext;

    public RatingContextService(RatingRepository ratingRepo, UserRepository userRepo,
                                ReportRepository reportRepo, ReportContextService reportContext) {
        this.ratingRepo = ratingRepo;
        this.userRepo = userRepo;
        this.reportRepo = reportRepo;
        this.reportContext = reportContext;
    }

    /**
     * @param canViewReports l'appelant a {@code REPORT_VIEW} : l'endpoint ne demande que
     *                       {@code RATING_MODERATE}, et les signalements (motif, nom du signalant) relèvent
     *                       d'un autre droit ; sans lui ils ne sont pas rendus
     */
    public AdminRatingContextResponse build(RatingEntity rating, boolean canViewReports) {
        UUID id = rating.getId();
        boolean withReports = canViewReports && id != null;
        UUID raterId = rating.getRaterId();
        UUID ratedId = rating.getRatedUserId();
        return new AdminRatingContextResponse(
                rater(raterId),
                rated(ratedId),
                raterId != null && ratedId != null ? ratingRepo.countByRaterIdAndRatedUserId(raterId, ratedId) : 0,
                withReports ? reportContext.toRelated(
                        reportRepo.findTop10ByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType.RATING, id)) : List.of(),
                withReports ? reportRepo.countByTargetTypeAndTargetIdAndStatus(ReportTargetType.RATING, id, ReportStatus.OPEN) : 0,
                !canViewReports,
                reportContext.history("RATING", id));
    }

    private Rater rater(UUID raterId) {
        if (raterId == null) {
            return null;
        }
        return userRepo.findById(raterId)
                .map(u -> new Rater(
                        u.getId(), MatchingTextUtil.buildName(u), u.getCreatedAt(),
                        ratingRepo.countByRaterId(raterId),
                        oneDecimal(ratingRepo.averageStarsByRaterId(raterId)),
                        ratingRepo.countByRaterIdAndStarsLessThanEqual(raterId, LOW_STARS)))
                .orElse(null);
    }

    private Rated rated(UUID ratedId) {
        if (ratedId == null) {
            return null;
        }
        return userRepo.findById(ratedId)
                .map(u -> new Rated(
                        u.getId(), MatchingTextUtil.buildName(u), u.getCreatedAt(),
                        u.getAverageRating(), u.getRatingCount(),
                        ratingRepo.countByRatedUserIdAndExcludedFromAverageTrue(ratedId),
                        distribution(ratedId)))
                .orElse(null);
    }

    private Map<Integer, Long> distribution(UUID ratedId) {
        Map<Integer, Long> byStars = new TreeMap<>();
        for (Object[] row : ratingRepo.countIncludedByStars(ratedId)) {
            byStars.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return byStars;
    }

    private static BigDecimal oneDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
