package com.yadony.api.admin.dto;

import java.util.UUID;

/**
 * Résultat de POST /admin/reports/{id}/reply : la conversation support (forme du détail
 * de la page Support) et si elle vient d'être ouverte ({@code created}) ou si la réponse
 * s'est ajoutée au ticket déjà lié.
 */
public record AdminReportReplyResponse(
        UUID ticketId,
        boolean created,
        AdminSupportTicketResponse ticket) {
}
