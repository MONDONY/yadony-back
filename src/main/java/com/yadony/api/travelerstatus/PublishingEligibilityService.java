package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.gppro.GpProApplicationRepository;
import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.travelerstatus.dto.PublishingEligibilityResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.Set;

/**
 * Décide si un voyageur peut publier un trajet maintenant, d'après la limite mensuelle réglée dans l'admin
 * ({@code users.monthly_trip_limit}).
 *
 * <ul>
 *   <li>GP Pro validé ou limite nulle : toujours {@code OK}, {@code limit = null} (un GP Pro n'est jamais
 *       limité, même si une valeur est saisie).</li>
 *   <li>{@code used < limit} : {@code OK}.</li>
 *   <li>{@code used >= limit} : demande GP Pro ouverte, {@code GP_PRO_PENDING} ; sinon statut jamais
 *       déclaré, {@code STATUS_REQUIRED} ; sinon {@code LIMIT_REACHED}.</li>
 * </ul>
 */
@Service
public class PublishingEligibilityService {

    /** Demandes ouvertes : en attente de l'identité vérifiée ou de la décision de l'équipe. */
    private static final Set<GpProApplicationStatus> OPEN_APPLICATION =
            EnumSet.of(GpProApplicationStatus.AWAITING_KYC, GpProApplicationStatus.PENDING);

    private final AnnouncementRepository announcementRepository;
    private final GpProApplicationRepository applicationRepository;
    private final Clock clock;

    public PublishingEligibilityService(AnnouncementRepository announcementRepository,
                                        GpProApplicationRepository applicationRepository,
                                        Clock clock) {
        this.announcementRepository = announcementRepository;
        this.applicationRepository = applicationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublishingEligibilityResponse evaluate(UserEntity user) {
        YearMonth month = YearMonth.now(clock);
        LocalDate resetsOn = month.plusMonths(1).atDay(1);
        int used = usedThisMonth(user, month);

        boolean unlimited = user.isGpPro() || user.getMonthlyTripLimit() == null;
        if (unlimited) {
            return response(PublishingEligibilityReason.OK, null, used, resetsOn, user);
        }
        int limit = user.getMonthlyTripLimit();
        if (used < limit) {
            return response(PublishingEligibilityReason.OK, limit, used, resetsOn, user);
        }
        PublishingEligibilityReason reason;
        if (applicationRepository.findFirstByUserIdAndStatusIn(user.getId(), OPEN_APPLICATION).isPresent()) {
            reason = PublishingEligibilityReason.GP_PRO_PENDING;
        } else if (user.getTravelerStatus() == null) {
            reason = PublishingEligibilityReason.STATUS_REQUIRED;
        } else {
            reason = PublishingEligibilityReason.LIMIT_REACHED;
        }
        return response(reason, limit, used, resetsOn, user);
    }

    /** Trajets publiés (hors brouillons) créés dans le mois civil : même fenêtre que la limite PRO existante. */
    public int usedThisMonth(UserEntity user) {
        return usedThisMonth(user, YearMonth.now(clock));
    }

    private int usedThisMonth(UserEntity user, YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.atEndOfMonth().atTime(23, 59, 59);
        long count = announcementRepository.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                user.getId(), from, to, AnnouncementStatus.DRAFT);
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    private static PublishingEligibilityResponse response(PublishingEligibilityReason reason, Integer limit,
                                                          int used, LocalDate resetsOn, UserEntity user) {
        return new PublishingEligibilityResponse(reason == PublishingEligibilityReason.OK, reason, limit, used,
                resetsOn, user.getTravelerStatus());
    }
}
