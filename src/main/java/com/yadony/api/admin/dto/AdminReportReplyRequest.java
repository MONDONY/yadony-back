package com.yadony.api.admin.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Réponse de l'admin au signalant d'un bug de l'app. Texte OU pièce jointe (règle du
 * service support) ; les pièces jointes sont des clés déjà envoyées par
 * POST /admin/support/tickets/attachments (préfixe support/admin/{adminId}/).
 */
public record AdminReportReplyRequest(
        @Size(max = 4000) String message,
        List<String> attachmentKeys) {
}
