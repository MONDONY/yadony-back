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
        long unreadCount,
        String lastMessagePreview,
        Boolean lastMessageFromAdmin) {

    /** Vue liste : le fil n'est pas charge. */
    public static SupportTicketResponse summary(SupportTicketEntity ticket, long unreadCount) {
        return build(ticket, null, unreadCount, null, null);
    }

    /**
     * Vue liste avec l'apercu du dernier message ({@code lastMessageAt} est deja
     * celui du ticket). Champs absents du JSON si le fil est vide.
     */
    public static SupportTicketResponse summary(SupportTicketEntity ticket, long unreadCount,
                                                String lastMessagePreview, Boolean lastMessageFromAdmin) {
        return build(ticket, null, unreadCount, lastMessagePreview, lastMessageFromAdmin);
    }

    /** Vue detail : le fil complet, du plus ancien au plus recent. */
    public static SupportTicketResponse withMessages(SupportTicketEntity ticket,
                                                     List<SupportMessageResponse> messages,
                                                     long unreadCount) {
        return build(ticket, messages, unreadCount, null, null);
    }

    private static SupportTicketResponse build(SupportTicketEntity ticket,
                                               List<SupportMessageResponse> messages,
                                               long unreadCount,
                                               String lastMessagePreview,
                                               Boolean lastMessageFromAdmin) {
        return new SupportTicketResponse(
                ticket.getId(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getStatus().name(),
                ticket.getCreatedAt(),
                ticket.getLastMessageAt(),
                ticket.getResolvedAt(),
                messages,
                unreadCount,
                lastMessagePreview,
                lastMessageFromAdmin);
    }
}
