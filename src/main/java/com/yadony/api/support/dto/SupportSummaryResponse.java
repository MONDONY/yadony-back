package com.yadony.api.support.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Resume de la boite support pour la tuile « Yadony Support » de l'onglet
 * Messages. {@code latestTicket} est le ticket non resolu le plus recent, a
 * defaut le plus recent tout court, et {@code null} sans aucun ticket : il est
 * alors serialise explicitement a null (l'API omet d'ordinaire les nulls).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SupportSummaryResponse(
        long unreadCount,
        long openTicketCount,
        LatestTicket latestTicket) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LatestTicket(
            UUID id,
            String subject,
            String lastMessagePreview,
            LocalDateTime lastMessageAt,
            boolean lastMessageFromAdmin,
            long unreadCount) {
    }
}
