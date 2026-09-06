package com.yadony.api.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicketEntity, UUID> {

    Page<SupportTicketEntity> findByUserIdOrderByLastMessageAtDesc(UUID userId, Pageable pageable);

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
}
