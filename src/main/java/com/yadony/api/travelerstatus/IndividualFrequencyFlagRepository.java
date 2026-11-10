package com.yadony.api.travelerstatus;

import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProTravelFrequency;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidStatus;
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
     * sans demande GP Pro en cours d'examen, et leurs VOYAGES (dates de départ distinctes) parmi les trajets ayant au
     * moins une réservation dans {@code bidStatuses} créée depuis {@code since}, au-delà de {@code threshold}
     * (strictement). Compter les dates plutôt que les annonces : un seul vol peut porter plusieurs annonces (trajets
     * dédiés créés en répondant à des demandes d'envoi, ou une annonce par ville d'arrivée). Lecture seule.
     *
     * @return lignes {@code [travelerId (UUID), tripsCount (Long)]}
     */
    @Query("""
        SELECT a.travelerId, COUNT(DISTINCT a.departureDate)
        FROM BidEntity b, AnnouncementEntity a, UserEntity u
        WHERE b.announcementId = a.id AND u.id = a.travelerId
          AND b.status IN :bidStatuses AND b.createdAt >= :since
          AND a.status NOT IN :excludedTripStatuses
          AND u.gpPro = false AND u.travelerStatus = com.yadony.api.travelerstatus.TravelerStatus.INDIVIDUAL
          AND u.status = com.yadony.api.auth.UserStatus.ACTIVE AND u.deletionRequestedAt IS NULL
          AND b.deletedAt IS NULL AND a.deletedAt IS NULL AND u.deletedAt IS NULL
          AND NOT EXISTS (SELECT 1 FROM GpProApplicationEntity o
                          WHERE o.userId = u.id AND o.status IN :openApplications AND o.deletedAt IS NULL)
        GROUP BY a.travelerId
        HAVING COUNT(DISTINCT a.departureDate) > :threshold
    """)
    List<Object[]> countTripsAboveThreshold(@Param("bidStatuses") Collection<BidStatus> bidStatuses,
                                            @Param("excludedTripStatuses") Collection<AnnouncementStatus> excludedTripStatuses,
                                            @Param("openApplications") Collection<GpProApplicationStatus> openApplications,
                                            @Param("since") LocalDateTime since,
                                            @Param("threshold") long threshold);

    /** Même compte (dates de départ distinctes) que {@link #countTripsAboveThreshold}, pour des voyageurs donnés, sans seuil. */
    @Query("""
        SELECT a.travelerId, COUNT(DISTINCT a.departureDate)
        FROM BidEntity b, AnnouncementEntity a
        WHERE b.announcementId = a.id AND a.travelerId IN :travelerIds
          AND b.status IN :bidStatuses AND b.createdAt >= :since
          AND a.status NOT IN :excludedTripStatuses
          AND b.deletedAt IS NULL AND a.deletedAt IS NULL
        GROUP BY a.travelerId
    """)
    List<Object[]> countTrips(@Param("travelerIds") Collection<UUID> travelerIds,
                              @Param("bidStatuses") Collection<BidStatus> bidStatuses,
                              @Param("excludedTripStatuses") Collection<AnnouncementStatus> excludedTripStatuses,
                              @Param("since") LocalDateTime since);

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
