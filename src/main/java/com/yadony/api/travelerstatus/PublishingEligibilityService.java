package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.gppro.GpProApplicationRepository;
import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.matching.AnnouncementRepository;
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
    private final UserRepository userRepository;
    private final Clock clock;

    public PublishingEligibilityService(AnnouncementRepository announcementRepository,
                                        GpProApplicationRepository applicationRepository,
                                        UserRepository userRepository,
                                        Clock clock) {
        this.announcementRepository = announcementRepository;
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** Éligibilité en lecture seule (écran, admin) : ne verrouille rien. */
    @Transactional(readOnly = true)
    public PublishingEligibilityResponse evaluate(UserEntity user) {
        return decide(user, 1);
    }

    /**
     * Éligibilité au moment de publier. À appeler DANS la transaction qui enregistre le trajet : quand une
     * limite s'applique (limite non nulle et pas GP Pro), la ligne de l'utilisateur est verrouillée AVANT de
     * compter et jusqu'à la validation du trajet, sinon deux publications simultanées passeraient toutes les
     * deux sous la limite. Sans limite applicable (la grande majorité des utilisateurs), aucun verrou.
     */
    @Transactional
    public PublishingEligibilityResponse evaluateForPublishing(UserEntity user) {
        return evaluateForPublishing(user, 1);
    }

    /**
     * Comme {@link #evaluateForPublishing(UserEntity)} pour {@code trips} trajets publiés d'un coup (un voyage
     * multi-étapes : chaque étape compte pour un trajet). Refusé dès que {@code used + trips} dépasse la limite,
     * pour qu'aucune étape ne soit créée.
     */
    @Transactional
    public PublishingEligibilityResponse evaluateForPublishing(UserEntity user, int trips) {
        UserEntity subject = user;
        if (!user.isGpPro() && user.getMonthlyTripLimit() != null) {
            subject = userRepository.findByIdForUpdate(user.getId()).orElse(user);
        }
        return decide(subject, Math.max(trips, 1));
    }

    private PublishingEligibilityResponse decide(UserEntity user, int trips) {
        YearMonth month = YearMonth.now(clock);
        LocalDate resetsOn = month.plusMonths(1).atDay(1);
        int used = usedThisMonth(user, month);

        boolean unlimited = user.isGpPro() || user.getMonthlyTripLimit() == null;
        if (unlimited) {
            return response(PublishingEligibilityReason.OK, null, used, resetsOn, user);
        }
        int limit = user.getMonthlyTripLimit();
        if (used + trips <= limit) {
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

    /** Trajets publiés (hors brouillons) dans le mois civil, brouillons publiés ce mois-ci compris. */
    public int usedThisMonth(UserEntity user) {
        return usedThisMonth(user, YearMonth.now(clock));
    }

    private int usedThisMonth(UserEntity user, YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        // Intervalle semi-ouvert [1er du mois, 1er du mois suivant[.
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        long count = announcementRepository.countPublishedInPeriod(user.getId(), from, to);
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    private static PublishingEligibilityResponse response(PublishingEligibilityReason reason, Integer limit,
                                                          int used, LocalDate resetsOn, UserEntity user) {
        return new PublishingEligibilityResponse(reason == PublishingEligibilityReason.OK, reason, limit, used,
                resetsOn, user.getTravelerStatus());
    }
}
