package com.yadony.api.admin.broadcast;

import com.yadony.api.auth.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Resolution des destinataires d'un broadcast. Requetes natives : elles ne renvoient que
 * des identifiants, jamais des entites — charger 50 000 {@code UserEntity} pour lire leur
 * id saturerait le contexte de persistance.
 *
 * <p>Le pilote PostgreSQL comme H2 mappent une colonne {@code uuid} sur
 * {@code java.util.UUID}, la projection {@code Page<UUID>} est donc directe.
 */
public interface BroadcastAudienceRepository extends Repository<UserEntity, UUID> {

    String ACTIVE = "u.deleted_at IS NULL AND u.status = 'ACTIVE'";

    @Query(value = "SELECT u.id FROM users u WHERE " + ACTIVE + " ORDER BY u.created_at",
           countQuery = "SELECT COUNT(*) FROM users u WHERE " + ACTIVE,
           nativeQuery = true)
    Page<UUID> findActiveIds(Pageable pageable);

    /** Expediteur = a cree au moins un bid. Jamais « porte le role SENDER » (cf. V193). */
    @Query(value = "SELECT u.id FROM users u WHERE " + ACTIVE
            + " AND EXISTS (SELECT 1 FROM bids b WHERE b.sender_id = u.id AND b.deleted_at IS NULL)"
            + " ORDER BY u.created_at",
           countQuery = "SELECT COUNT(*) FROM users u WHERE " + ACTIVE
            + " AND EXISTS (SELECT 1 FROM bids b WHERE b.sender_id = u.id AND b.deleted_at IS NULL)",
           nativeQuery = true)
    Page<UUID> findActiveSenderIds(Pageable pageable);

    /** Voyageur = a publie au moins une annonce. Jamais « porte le role TRAVELER » (cf. V193). */
    @Query(value = "SELECT u.id FROM users u WHERE " + ACTIVE
            + " AND EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id AND a.deleted_at IS NULL)"
            + " ORDER BY u.created_at",
           countQuery = "SELECT COUNT(*) FROM users u WHERE " + ACTIVE
            + " AND EXISTS (SELECT 1 FROM announcements a WHERE a.traveler_id = u.id AND a.deleted_at IS NULL)",
           nativeQuery = true)
    Page<UUID> findActiveTravelerIds(Pageable pageable);

    String CORRIDOR_PREDICATE = """
             AND (EXISTS (SELECT 1 FROM announcements a
                          WHERE a.traveler_id = u.id AND a.deleted_at IS NULL
                            AND a.departure_city ILIKE :origin AND a.arrival_city ILIKE :destination)
               OR EXISTS (SELECT 1 FROM bids b
                          JOIN announcements a2 ON a2.id = b.announcement_id
                          WHERE b.sender_id = u.id AND b.deleted_at IS NULL AND a2.deleted_at IS NULL
                            AND a2.departure_city ILIKE :origin AND a2.arrival_city ILIKE :destination))
            """;

    @Query(value = "SELECT u.id FROM users u WHERE " + ACTIVE + CORRIDOR_PREDICATE + " ORDER BY u.created_at",
           countQuery = "SELECT COUNT(*) FROM users u WHERE " + ACTIVE + CORRIDOR_PREDICATE,
           nativeQuery = true)
    Page<UUID> findActiveCorridorIds(@Param("origin") String origin,
                                     @Param("destination") String destination,
                                     Pageable pageable);

    /**
     * Ciblage nominatif. Le filtre de statut est volontairement absent : un administrateur
     * doit pouvoir prevenir un compte suspendu de sa suspension. Seule la suppression
     * (soft delete) ecarte le compte.
     */
    @Query(value = "SELECT u.id FROM users u WHERE u.deleted_at IS NULL AND u.id = :userId",
           countQuery = "SELECT COUNT(*) FROM users u WHERE u.deleted_at IS NULL AND u.id = :userId",
           nativeQuery = true)
    Page<UUID> findExistingIdById(@Param("userId") UUID userId, Pageable pageable);
}
