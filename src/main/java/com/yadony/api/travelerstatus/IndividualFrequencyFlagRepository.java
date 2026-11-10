package com.yadony.api.travelerstatus;

import com.yadony.api.gppro.GpProApplicationStatus;
import com.yadony.api.gppro.GpProTravelFrequency;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /**
     * Particuliers sans badge GP Pro, compte actif (ni suspendu, ni banni, ni en cours de suppression, ni supprimé),
     * et leurs trajets distincts ayant au moins une réservation dans
     * {@code bidStatuses} créée depuis {@code since}, au-delà de {@code threshold} (strictement). Lecture seule.
     *
     * @return lignes {@code [travelerId (UUID), tripsCount (Long)]}
     */
    @Query("""
        SELECT a.travelerId, COUNT(DISTINCT a.id)
        FROM BidEntity b, AnnouncementEntity a, UserEntity u
        WHERE b.announcementId = a.id AND u.id = a.travelerId
          AND b.status IN :bidStatuses AND b.createdAt >= :since
          AND a.status NOT IN :excludedTripStatuses
          AND u.gpPro = false AND u.travelerStatus = com.yadony.api.travelerstatus.TravelerStatus.INDIVIDUAL
          AND u.status = com.yadony.api.auth.UserStatus.ACTIVE AND u.deletionRequestedAt IS NULL
          AND b.deletedAt IS NULL AND a.deletedAt IS NULL AND u.deletedAt IS NULL
        GROUP BY a.travelerId
        HAVING COUNT(DISTINCT a.id) > :threshold
    """)
    List<Object[]> countTripsAboveThreshold(@Param("bidStatuses") Collection<BidStatus> bidStatuses,
                                            @Param("excludedTripStatuses") Collection<AnnouncementStatus> excludedTripStatuses,
                                            @Param("since") LocalDateTime since,
                                            @Param("threshold") long threshold);

    /** Même compte que {@link #countTripsAboveThreshold}, pour des voyageurs donnés et sans seuil. */
    @Query("""
        SELECT a.travelerId, COUNT(DISTINCT a.id)
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
     * Particuliers sans badge GP Pro, compte actif (mêmes exclusions), ayant demandé le badge avec la fréquence {@code frequency} depuis {@code since},
     * demande close sans badge ({@code statuses} : refusée ou annulée). Lecture seule.
     */
    @Query("""
        SELECT DISTINCT g.userId
        FROM GpProApplicationEntity g, UserEntity u
        WHERE u.id = g.userId
          AND g.travelFrequency = :frequency AND g.status IN :statuses AND g.createdAt >= :since
          AND u.gpPro = false AND u.travelerStatus = com.yadony.api.travelerstatus.TravelerStatus.INDIVIDUAL
          AND u.status = com.yadony.api.auth.UserStatus.ACTIVE AND u.deletionRequestedAt IS NULL
          AND g.deletedAt IS NULL AND u.deletedAt IS NULL
    """)
    List<UUID> findIndividualsWithApplication(@Param("frequency") GpProTravelFrequency frequency,
                                              @Param("statuses") Collection<GpProApplicationStatus> statuses,
                                              @Param("since") LocalDateTime since);
}
