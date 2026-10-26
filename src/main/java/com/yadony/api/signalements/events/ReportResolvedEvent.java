package com.yadony.api.signalements.events;

import java.util.UUID;

/**
 * Un signalement vient d'être traité (statut RESOLVED, jamais au rejet). Publié dans la
 * transaction de résolution ; {@code notifications/} remercie le signalant après le commit.
 *
 * @param reporterId      signalant, {@code null} si inconnu ou supprimé (rien n'est alors envoyé)
 * @param reporterMessage mot du modérateur ajouté au remerciement, {@code null} ou vide = aucun
 */
public record ReportResolvedEvent(UUID reportId, UUID reporterId, String reporterMessage) {

    public ReportResolvedEvent(UUID reportId, UUID reporterId) {
        this(reportId, reporterId, null);
    }
}
