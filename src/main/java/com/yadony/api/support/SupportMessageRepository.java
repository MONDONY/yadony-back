package com.yadony.api.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SupportMessageRepository extends JpaRepository<SupportMessageEntity, UUID> {

    List<SupportMessageEntity> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    long countByTicketIdAndAuthorType(UUID ticketId, SupportMessageAuthorType authorType);

    long countByTicketIdAndAuthorTypeAndCreatedAtAfter(UUID ticketId,
                                                       SupportMessageAuthorType authorType,
                                                       LocalDateTime after);
}
