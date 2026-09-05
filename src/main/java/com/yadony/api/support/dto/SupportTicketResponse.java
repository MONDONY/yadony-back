package com.yadony.api.support.dto;

import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportMessageEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SupportTicketResponse(
        UUID id,
        String category,
        String subject,
        String status,
        LocalDateTime createdAt,
        LocalDateTime lastMessageAt,
        LocalDateTime resolvedAt,
        List<SupportMessageResponse> messages) {

    /** Vue liste : le fil n'est pas charge. */
    public static SupportTicketResponse summary(SupportTicketEntity ticket) {
        return build(ticket, null);
    }

    /** Vue detail : le fil complet, du plus ancien au plus recent. */
    public static SupportTicketResponse withMessages(SupportTicketEntity ticket,
                                                     List<SupportMessageEntity> messages) {
        return build(ticket, messages.stream().map(SupportMessageResponse::from).toList());
    }

    private static SupportTicketResponse build(SupportTicketEntity ticket,
                                               List<SupportMessageResponse> messages) {
        return new SupportTicketResponse(
                ticket.getId(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getStatus().name(),
                ticket.getCreatedAt(),
                ticket.getLastMessageAt(),
                ticket.getResolvedAt(),
                messages);
    }
}
