package com.yadony.api.admin.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'il faut à un modérateur pour trancher un signalement sans quitter son dossier. Lu à
 * l'ouverture du dossier, pas dans la liste : tout est calculé par signalement (décomptes,
 * lecture Firestore, audit) et ne saurait l'être pour une page entière sans la ralentir.
 *
 * @param reporter           le signalant et son historique de signalements ; {@code null} s'il est inconnu ou supprimé
 * @param subject            la personne visée (le compte signalé, ou l'auteur du contenu signalé) ;
 *                           {@code null} quand elle est inconnue ou que la cible est l'application
 * @param messages           le message signalé et ceux qui l'entourent, du plus ancien au plus récent ;
 *                           {@code null} hors cible MESSAGE, ou si Firestore n'a rien rendu
 * @param messagesRestricted vrai quand la cible est un message mais que l'appelant n'a pas
 *                           {@code MODERATION_VIEW} : il lit déjà le message signalé, pas la conversation
 * @param history            gestes tracés sur ce signalement, du plus récent au plus ancien
 * @param relatedReports     autres signalements sur la même cible (10 au plus), du plus récent au plus ancien
 */
public record AdminReportContextResponse(
        Reporter reporter,
        Subject subject,
        List<ContextMessage> messages,
        boolean messagesRestricted,
        List<HistoryEntry> history,
        List<RelatedReport> relatedReports
) {

    /** Compteurs du signalant : {@code resolved} = traités (toute action sauf rejet), {@code dismissed} = rejetés. */
    public record Reporter(UUID userId, String displayName, LocalDateTime memberSince,
                           long totalReports, long resolvedReports, long dismissedReports) {}

    /**
     * @param accountStatus     {@code ACTIVE}, {@code SUSPENDED}, {@code BANNED} ou {@code PENDING_DELETION}
     * @param identityVerified  contrôle d'identité (KYC) réussi
     * @param reportsReceived   signalements visant directement ce compte, tous statuts confondus
     * @param openReportsReceived dont encore ouverts
     */
    public record Subject(UUID userId, String displayName, LocalDateTime memberSince, String accountStatus,
                          boolean identityVerified, long reportsReceived, long openReportsReceived,
                          int cancellationCount) {}

    /**
     * @param flagged     le message signalé
     * @param fromSubject écrit par la personne visée (pour aligner la bulle)
     */
    public record ContextMessage(String id, String senderName, String body, String sentAt,
                                 boolean flagged, boolean deleted, boolean fromSubject) {}

    /**
     * @param action     code d'audit ({@code REPORT_RESOLVED}, {@code REPORT_DELETED}, {@code REPORT_RESTORED})
     * @param detail     action de modération prise ({@code WARN_AUTHOR}…), pour une résolution
     * @param actorEmail compte admin auteur du geste, {@code null} s'il est inconnu ou supprimé
     */
    public record HistoryEntry(String action, String detail, LocalDateTime at, String actorEmail, String note) {}

    public record RelatedReport(UUID id, String reason, String status, LocalDateTime createdAt, String reporterName) {}
}
