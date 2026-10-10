package com.yadony.api.disputes;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<DisputeEntity, UUID> {

    // Préserve le comportement existant (seul type en usage avant cette feature) —
    // aucun appelant existant à modifier. Délègue à la version paramétrée par
    // type pour éviter de dupliquer le JPQL (même filtre).
    default Optional<DisputeEntity> findByBidId(UUID bidId) {
        return findByBidIdAndType(bidId, "SENDER_NO_SHOW_CONTESTED");
    }

    /**
     * Un litige existe pour le bid, ouvert ou résolu, quel que soit son type : l'argent du colis
     * relève alors de la décision admin et le passage « non réclamé » ne verse rien seul.
     */
    boolean existsByBidId(UUID bidId);

    // Nouveau — idempotence par type pour les litiges d'arrivée.
    Optional<DisputeEntity> findByBidIdAndType(UUID bidId, String type);

    /** Litiges d'une page de bids en une requête (file admin des no-shows). */
    List<DisputeEntity> findByBidIdIn(java.util.Collection<UUID> bidIds);

    /** Litige non clos sur ce bid (rétention des photos de messagerie, FLUTTER-B4). */
    boolean existsByBidIdAndStatusNot(UUID bidId, String status);

    /** Litige de ce statut dont le type commence par ce préfixe (litiges admin : {@code ADMIN_}). */
    boolean existsByBidIdAndStatusAndTypeStartingWith(UUID bidId, String status, String typePrefix);

    List<DisputeEntity> findBySenderIdOrTravelerIdOrderByCreatedAtDesc(UUID senderId, UUID travelerId);

    @Query("SELECT d FROM DisputeEntity d WHERE (:status IS NULL OR d.status = :status) ORDER BY d.createdAt DESC")
    Page<DisputeEntity> findAdminFiltered(@Param("status") String status, Pageable pageable);

    List<DisputeEntity> findAllByCreatedAtBetweenOrderByCreatedAtAsc(
            java.time.LocalDateTime from, java.time.LocalDateTime to);

    List<DisputeEntity> findAllByResolutionTypeAndResolvedAtBetweenOrderByResolvedAtAsc(
            String resolutionType, java.time.OffsetDateTime from, java.time.OffsetDateTime to);
}
