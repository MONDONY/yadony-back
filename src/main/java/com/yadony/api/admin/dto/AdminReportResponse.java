package com.yadony.api.admin.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AdminReportResponse(
        UUID id,
        String targetType,
        UUID targetId,
        String targetLabel,
        String reporterName,
        String reason,
        String description,
        String status,
        String actionTaken,
        String resolutionNote,
        OffsetDateTime resolvedAt,
        LocalDateTime createdAt,
        List<String> photoUrls,
        /** Route de l'écran d'origine pour un rapport SCREEN_BUG, sinon null. */
        String screenRoute,
        /** Date de suppression (soft delete) ; {@code null} pour un signalement visible. */
        java.time.LocalDateTime deletedAt,
        /** Email de l'admin auteur de la suppression, lu dans l'audit {@code REPORT_DELETED}. */
        String deletedByAdminEmail,
        /**
         * Actions ({@code ReportAction}) que l'admin APPELANT peut lancer sur ce signalement :
         * type de cible × cible retrouvée × permissions. Vide si déjà traité, rejeté ou supprimé.
         */
        List<String> availableActions,
        /** Auteur du contenu signalé quand il est retrouvé (« Prénom N. »), sinon {@code null}. */
        TargetAuthor targetAuthor,
        /** Conversation support ouverte avec le signalant (POST /reply), sinon {@code null}. */
        UUID supportTicketId,
        /**
         * L'admin APPELANT peut-il répondre au signalant (POST /admin/reports/{id}/reply) :
         * cible APP, signalant encore présent, non supprimé, permissions REPORT_VIEW et
         * SUPPORT_TICKET_MANAGE. Indépendant du statut : répondre ne traite pas le signalement.
         */
        boolean canReply
) {
    public record TargetAuthor(UUID userId, String name) {}
}
