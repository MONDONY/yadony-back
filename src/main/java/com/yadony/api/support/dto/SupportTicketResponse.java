package com.yadony.api.support.dto;

import com.yadony.api.support.SupportTicketEntity;

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
        List<SupportMessageResponse> messages,
        long unreadCount) {

    /** Vue liste : le fil n'est pas charge. */
    public static SupportTicketResponse summary(SupportTicketEntity ticket, long unreadCount) {
        return build(ticket, null, unreadCount);
    }

    /** Vue detail : le fil complet, du plus ancien au plus recent. */
    public static SupportTicketResponse withMessages(SupportTicketEntity ticket,
                                                     List<SupportMessageResponse> messages,
                                                     long unreadCount) {
        return build(ticket, messages, unreadCount);
    }

    private static SupportTicketResponse build(SupportTicketEntity ticket,
                                               List<SupportMessageResponse> messages,
                                               long unreadCount) {
        return new SupportTicketResponse(
                ticket.getId(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getStatus().name(),
                ticket.getCreatedAt(),
                ticket.getLastMessageAt(),
                ticket.getResolvedAt(),
                messages,
                unreadCount);
    }
}
