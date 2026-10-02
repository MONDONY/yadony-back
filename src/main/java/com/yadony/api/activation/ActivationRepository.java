package com.yadony.api.activation;

import com.yadony.api.auth.UserEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Lectures transverses du guidage après KYC (SQL natif sur les tables des autres features). */
public interface ActivationRepository extends Repository<UserEntity, UUID> {

    /** Première action de u : trajet / demande publiés, bid, fil de négociation voyageur, alerte. */
    String HAS_FIRST_ACTION = "("
            + "EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id AND a.deleted_at IS NULL AND a.status <> 'DRAFT')"
            + " OR EXISTS (SELECT 1 FROM package_requests p WHERE p.sender_id = u.id AND p.deleted_at IS NULL AND p.status <> 'DRAFT')"
            + " OR EXISTS (SELECT 1 FROM bids b WHERE b.sender_id = u.id AND b.deleted_at IS NULL)"
            + " OR EXISTS (SELECT 1 FROM negotiation_threads n WHERE n.traveler_id = u.id AND n.deleted_at IS NULL)"
            + " OR EXISTS (SELECT 1 FROM corridor_alerts c WHERE c.owner_id = u.id AND c.deleted_at IS NULL))";

    String TRIP_TOWARDS = " FROM announcements a"
            + " WHERE a.deleted_at IS NULL AND a.status = 'ACTIVE'"
            + " AND a.departure_date BETWEEN :from AND :to"
            + " AND a.traveler_id <> :excluded"
            + " AND (UPPER(a.arrival_country_code) = :destination"
            + "      OR (a.arrival_country_code IS NULL AND EXISTS (SELECT 1 FROM cities ci"
            + "          WHERE LOWER(ci.name) = LOWER(a.arrival_city) AND UPPER(ci.country_code) = :destination)))";

    String PACKAGE_TOWARDS = " FROM package_requests p"
            + " WHERE p.deleted_at IS NULL AND p.status IN ('OPEN', 'NEGOTIATING')"
            + " AND p.desired_date BETWEEN :from AND :to"
            + " AND p.sender_id <> :excluded"
            + " AND EXISTS (SELECT 1 FROM cities ci"
            + "     WHERE LOWER(ci.name) = LOWER(p.arrival_city) AND UPPER(ci.country_code) = :destination)";

    @Query(value = "SELECT CASE WHEN " + HAS_FIRST_ACTION + " THEN 1 ELSE 0 END FROM users u WHERE u.id = :userId",
           nativeQuery = true)
    int hasFirstAction(@Param("userId") UUID userId);

    @Query(value = "SELECT CAST(a.id AS VARCHAR) AS id, a.departure_city AS departureCity, a.arrival_city AS arrivalCity,"
            + " a.departure_date AS departureDate, a.available_kg AS availableKg, a.price_per_kg AS pricePerKg,"
            + " a.currency AS currency" + TRIP_TOWARDS + " ORDER BY a.departure_date ASC LIMIT :limit",
           nativeQuery = true)
    List<TripRow> findTripsTowards(@Param("destination") String destination, @Param("from") LocalDate from,
                                   @Param("to") LocalDate to, @Param("excluded") UUID excludedUserId,
                                   @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*)" + TRIP_TOWARDS, nativeQuery = true)
    long countTripsTowards(@Param("destination") String destination, @Param("from") LocalDate from,
                           @Param("to") LocalDate to, @Param("excluded") UUID excludedUserId);

    @Query(value = "SELECT CAST(p.id AS VARCHAR) AS id, p.departure_city AS departureCity, p.arrival_city AS arrivalCity,"
            + " p.desired_date AS desiredDate, p.weight_kg AS weightKg" + PACKAGE_TOWARDS
            + " ORDER BY p.desired_date ASC LIMIT :limit",
           nativeQuery = true)
    List<PackageRow> findPackagesTowards(@Param("destination") String destination, @Param("from") LocalDate from,
                                         @Param("to") LocalDate to, @Param("excluded") UUID excludedUserId,
                                         @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*)" + PACKAGE_TOWARDS, nativeQuery = true)
    long countPackagesTowards(@Param("destination") String destination, @Param("from") LocalDate from,
                              @Param("to") LocalDate to, @Param("excluded") UUID excludedUserId);

    @Query(value = "SELECT CAST(u.id AS VARCHAR) AS id FROM users u"
            + " WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' AND u.kyc_status = 'VERIFIED'"
            + " AND u.kyc_verified_at IS NOT NULL"
            + " AND ((u.first_action_reminder_count = 0 AND u.kyc_verified_at <= :firstDueBefore)"
            + "   OR (u.first_action_reminder_count = 1 AND u.kyc_verified_at <= :secondDueBefore))"
            + " AND NOT " + HAS_FIRST_ACTION
            + " ORDER BY u.kyc_verified_at ASC LIMIT 500",
           nativeQuery = true)
    List<CandidateRow> findFirstActionReminderCandidateRows(@Param("firstDueBefore") Instant firstDueBefore,
                                                            @Param("secondDueBefore") Instant secondDueBefore);

    /** Comptes à relancer. Les ids natifs sont lus en VARCHAR (H2 les rend en byte[]), comme AnnouncementRepository. */
    default List<UUID> findFirstActionReminderCandidates(Instant firstDueBefore, Instant secondDueBefore) {
        return findFirstActionReminderCandidateRows(firstDueBefore, secondDueBefore).stream()
                .map(row -> UUID.fromString(row.getId()))
                .toList();
    }

    interface CandidateRow {
        String getId();
    }

    interface TripRow {
        String getId();
        String getDepartureCity();
        String getArrivalCity();
        LocalDate getDepartureDate();
        Number getAvailableKg();
        Number getPricePerKg();
        String getCurrency();
    }

    interface PackageRow {
        String getId();
        String getDepartureCity();
        String getArrivalCity();
        LocalDate getDesiredDate();
        Number getWeightKg();
    }
}
