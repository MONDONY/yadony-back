package com.yadony.api.travelerstatus;

import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProTravelFrequency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IndividualFrequencyFlagRepository extends JpaRepository<IndividualFrequencyFlagEntity, UUID> {

    List<IndividualFrequencyFlagEntity> findByResolvedAtIsNull();

    /** File admin : ni résolus ni écartés, le plus ancien d'abord. */
    List<IndividualFrequencyFlagEntity> findByResolvedAtIsNullAndDismissedAtIsNullOrderByDetectedAtAscIdAsc();

    Optional<IndividualFrequencyFlagEntity> findFirstByUserIdAndResolvedAtIsNullAndDismissedAtIsNull(UUID userId);

    /** Un signalement (résolu, écarté ou non) détecté depuis {@code since} : la question a déjà pu être posée. */
    boolean existsByUserIdAndDetectedAtGreaterThanEqual(UUID userId, LocalDateTime since);

    /**
     * Écarte un signalement encore ouvert, en une seule écriture conditionnelle : un double clic ou une résolution
     * simultanée par le contrôle ne produisent ni double audit ni écrasement.
     *
     * @return 1 si le signalement a été écarté par cet appel, 0 sinon (déjà écarté, résolu ou inconnu)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE IndividualFrequencyFlagEntity f
        SET f.dismissedAt = :now, f.dismissedBy = :adminId, f.updatedAt = :now
        WHERE f.id = :id AND f.dismissedAt IS NULL AND f.resolvedAt IS NULL AND f.deletedAt IS NULL
    """)
    int dismissIfOpen(@Param("id") UUID id, @Param("adminId") UUID adminId, @Param("now") LocalDateTime now);

    /** Résout un signalement encore ouvert sans toucher aux colonnes « écarté » (pas d'écrasement concurrent). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE IndividualFrequencyFlagEntity f
        SET f.resolvedAt = :now, f.updatedAt = :now
        WHERE f.id = :id AND f.resolvedAt IS NULL
    """)
    int resolveIfOpen(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * Particuliers sans badge GP Pro, compte actif (ni suspendu, ni banni, ni en cours de suppression, ni supprimé),
     * sans demande GP Pro en cours d'examen, et leurs TRAJETS PUBLIÉS dans {@code [from, to[} (mois civil), au-delà de
     * {@code threshold} (strictement). Avec ou sans réservation. Même définition que la limite mensuelle de trajets
     * ({@code AnnouncementRepository#countPublishedInPeriod}, {@code PublishingEligibilityService}) : un trajet compte
     * si sa date de publication ({@code published_at}) tombe dans la période, un brouillon jamais publié (date nulle)
     * non, supprimer un trajet ne le retire pas du compte (SQL natif, volontairement SANS filtre {@code deleted_at}
     * sur les trajets), chaque étape d'un voyage multi-étapes compte pour un trajet. Seule différence voulue avec le
     * quota : les trajets annulés ou retirés par la modération ({@code excludedTripStatuses}) ne comptent pas ici, on
     * ne signale pas quelqu'un pour des trajets qui n'ont pas eu lieu. Lecture seule.
     *
     * @return lignes {@code [travelerId (UUID), tripsCount (Number)]}
     */
    @Query(value = """
        SELECT a.traveler_id, COUNT(*)
        FROM announcements a JOIN users u ON u.id = a.traveler_id
        WHERE a.published_at >= :from AND a.published_at < :to
          AND a.status NOT IN (:excludedTripStatuses)
          AND u.gp_pro = false AND u.traveler_status = 'INDIVIDUAL'
          AND u.status = 'ACTIVE' AND u.deletion_requested_at IS NULL AND u.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM gp_pro_applications o
                          WHERE o.user_id = u.id AND o.status IN (:openApplications) AND o.deleted_at IS NULL)
        GROUP BY a.traveler_id
        HAVING COUNT(*) > :threshold
    """, nativeQuery = true)
    List<Object[]> countPublishedTripsAboveThreshold(@Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to,
                                                     @Param("excludedTripStatuses") Collection<String> excludedTripStatuses,
                                                     @Param("openApplications") Collection<String> openApplications,
                                                     @Param("threshold") long threshold);

    /** Même compte que {@link #countPublishedTripsAboveThreshold}, pour des voyageurs donnés, sans seuil. */
    @Query(value = """
        SELECT a.traveler_id, COUNT(*)
        FROM announcements a
        WHERE a.traveler_id IN (:travelerIds)
          AND a.published_at >= :from AND a.published_at < :to
          AND a.status NOT IN (:excludedTripStatuses)
        GROUP BY a.traveler_id
    """, nativeQuery = true)
    List<Object[]> countPublishedTrips(@Param("travelerIds") Collection<UUID> travelerIds,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("excludedTripStatuses") Collection<String> excludedTripStatuses);

    /**
     * Particuliers sans badge GP Pro, compte actif (mêmes exclusions), sans demande en cours d'examen, dont une
     * demande GP Pro de fréquence {@code frequency} a été close sans badge ({@code statuses} : refusée ou annulée)
     * depuis {@code since}. Date de clôture : {@code decided_at} (refus), à défaut {@code updated_at} (annulation,
     * qui ne pose pas {@code decided_at}). Lecture seule.
     */
    @Query("""
        SELECT DISTINCT g.userId
        FROM GpProApplicationEntity g, UserEntity u
        WHERE u.id = g.userId
          AND g.travelFrequency = :frequency AND g.status IN :statuses
          AND COALESCE(g.decidedAt, g.updatedAt) >= :since
          AND u.gpPro = false AND u.travelerStatus = com.yadony.api.travelerstatus.TravelerStatus.INDIVIDUAL
          AND u.status = com.yadony.api.auth.UserStatus.ACTIVE AND u.deletionRequestedAt IS NULL
          AND g.deletedAt IS NULL AND u.deletedAt IS NULL
          AND NOT EXISTS (SELECT 1 FROM GpProApplicationEntity o
                          WHERE o.userId = u.id AND o.status IN :openApplications AND o.deletedAt IS NULL)
    """)
    List<UUID> findIndividualsWithApplication(@Param("frequency") GpProTravelFrequency frequency,
                                              @Param("statuses") Collection<GpProApplicationStatus> statuses,
                                              @Param("openApplications") Collection<GpProApplicationStatus> openApplications,
                                              @Param("since") LocalDateTime since);
}
