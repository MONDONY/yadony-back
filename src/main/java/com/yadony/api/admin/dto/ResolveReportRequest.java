package com.yadony.api.admin.dto;

import com.yadony.api.signalements.ReportAction;
import jakarta.validation.constraints.Size;

/**
 * Résolution d'un signalement. Trois textes, trois destinations distinctes.
 *
 * <p>La note servait auparavant les trois usages : elle devenait le corps de l'avertissement
 * lu par la personne signalée, si bien qu'y écrire « signalé par X » désignait le signalant à
 * celui que l'on sanctionnait.
 *
 * <ul>
 *   <li>{@code note} — interne. Note de résolution, journal d'audit, motif des sanctions.
 *       Jamais transmise à la personne signalée.</li>
 *   <li>{@code targetMessage} — corps de l'avertissement ({@code WARN}, {@code WARN_AUTHOR}),
 *       lu par la personne avertie seule. Facultatif : un texte par défaut sinon.</li>
 *   <li>{@code reporterMessage} — mot ajouté au remerciement du signalant, lu par lui seul.
 *       Facultatif.</li>
 * </ul>
 */
public record ResolveReportRequest(
        ReportAction action,

        @Size(max = 2000, message = "La note interne ne peut dépasser 2000 caractères")
        String note,

        @Size(max = 1000, message = "Le message à la personne signalée ne peut dépasser 1000 caractères")
        String targetMessage,

        @Size(max = 1000, message = "Le message au signalant ne peut dépasser 1000 caractères")
        String reporterMessage
) {

    /** Clients antérieurs aux deux messages : ni avertissement personnalisé ni mot au signalant. */
    public ResolveReportRequest(ReportAction action, String note) {
        this(action, note, null, null);
    }
}
