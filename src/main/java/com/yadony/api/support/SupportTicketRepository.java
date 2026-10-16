package com.yadony.api.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicketEntity, UUID> {

    Page<SupportTicketEntity> findByUserIdOrderByLastMessageAtDesc(UUID userId, Pageable pageable);

    /** Boîte support de l'utilisateur : sans les tickets qu'il a masqués. */
    Page<SupportTicketEntity> findByUserIdAndHiddenByUserAtIsNullOrderByLastMessageAtDesc(
            UUID userId, Pageable pageable);

    List<SupportTicketEntity> findByUserIdAndHiddenByUserAtIsNull(UUID userId);

    Page<SupportTicketEntity> findByUserIdAndStatusOrderByLastMessageAtDesc(
            UUID userId, SupportTicketStatus status, Pageable pageable);

    // Les six variantes admin sont volontairement derivees plutot qu'une seule
    // @Query a parametres nullables : un `:status IS NULL` sur un parametre enum
    // se comporte differemment selon le dialecte et a deja produit des 500 ici.
    Page<SupportTicketEntity> findAllByOrderByLastMessageAtDesc(Pageable pageable);

    Page<SupportTicketEntity> findByStatusOrderByLastMessageAtDesc(
            SupportTicketStatus status, Pageable pageable);

    Page<SupportTicketEntity> findByAssignedAdminIdIsNullOrderByLastMessageAtDesc(Pageable pageable);

    Page<SupportTicketEntity> findByAssignedAdminIdIsNullAndStatusOrderByLastMessageAtDesc(
            SupportTicketStatus status, Pageable pageable);

    Page<SupportTicketEntity> findByAssignedAdminIdOrderByLastMessageAtDesc(
            UUID assignedAdminId, Pageable pageable);

    Page<SupportTicketEntity> findByAssignedAdminIdAndStatusOrderByLastMessageAtDesc(
            UUID assignedAdminId, SupportTicketStatus status, Pageable pageable);

    long countByAssignedAdminIdIsNull();

    List<SupportTicketEntity> findByUserId(UUID userId);

    /**
     * Recherche admin : identifiant du ticket ou de l'utilisateur, sujet, ou nom de l'utilisateur
     * ({@code like} en minuscules, encadré de %). Plus récemment actifs d'abord.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT t FROM SupportTicketEntity t
            WHERE (:id IS NOT NULL AND (t.id = :id OR t.userId = :id))
               OR (:like IS NOT NULL AND (LOWER(t.subject) LIKE :like
                    OR t.userId IN (SELECT u.id FROM com.yadony.api.auth.UserEntity u
                        WHERE LOWER(CONCAT(COALESCE(u.firstName, ''), ' ', COALESCE(u.lastName, ''))) LIKE :like)))
            ORDER BY t.lastMessageAt DESC
            """)
    Page<SupportTicketEntity> searchAdmin(@org.springframework.data.repository.query.Param("id") UUID id,
                                          @org.springframework.data.repository.query.Param("like") String like,
                                          Pageable pageable);
}
