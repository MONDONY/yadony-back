package com.yadony.api.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportMessageRepository extends JpaRepository<SupportMessageEntity, UUID> {

    List<SupportMessageEntity> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    Optional<SupportMessageEntity> findFirstByTicketIdOrderByCreatedAtDesc(UUID ticketId);

    /** Le dernier message de chacun des tickets donnés (deux messages de même instant : l'un ou l'autre). */
    @Query("SELECT m FROM SupportMessageEntity m WHERE m.ticketId IN :ids AND m.createdAt = "
            + "(SELECT MAX(m2.createdAt) FROM SupportMessageEntity m2 WHERE m2.ticketId = m.ticketId)")
    List<SupportMessageEntity> findLatestOfTickets(@Param("ids") java.util.Collection<UUID> ids);

    long countByTicketIdAndAuthorType(UUID ticketId, SupportMessageAuthorType authorType);

    long countByTicketIdAndAuthorTypeAndCreatedAtAfter(UUID ticketId,
                                                       SupportMessageAuthorType authorType,
                                                       LocalDateTime after);
}
