package com.yadony.api.signalements;

/**
 * Actions possibles à la résolution d'un signalement (voir AdminReportsController.resolveReport).
 *
 * <p>Chaque action délègue à un service de modération déjà existant, sans rien réimplémenter :
 * <ul>
 *   <li>{@code RESOLVE} : « marquer comme traité », passage en RESOLVED avec la note, sans autre
 *       geste. Seul moyen de clore un signalement de l'app (scarabée) autrement qu'en le rejetant.</li>
 *   <li>{@code DISMISS} : rejet (DISMISSED).</li>
 *   <li>{@code WARN}, {@code SUSPEND_TARGET} : la cible est un compte ({@code USER}).</li>
 *   <li>{@code REMOVE_CONTENT} : retrait d'une annonce ou d'une demande d'envoi.</li>
 *   <li>{@code DELETE_MESSAGE} : suppression douce du message signalé.</li>
 *   <li>{@code EXCLUDE_RATING}, {@code DELETE_RATING} : exclusion de la moyenne ou suppression de l'avis.</li>
 *   <li>{@code WARN_AUTHOR}, {@code SUSPEND_AUTHOR} : avertir ou suspendre l'auteur du contenu
 *       signalé (auteur du message, de l'avis, ou partie adverse d'une offre).</li>
 * </ul>
 * Toute autre combinaison est refusée (422 {@code action-not-applicable}) — voir {@code appliesTo}.
 *
 * <p>{@code reports.action_taken} est un {@code VARCHAR(40)} sans CHECK (V164) : une nouvelle
 * valeur n'exige pas de migration tant qu'elle tient en 40 caractères.
 */
public enum ReportAction {
    DISMISS,
    WARN,
    SUSPEND_TARGET,
    REMOVE_CONTENT,
    RESOLVE,
    DELETE_MESSAGE,
    EXCLUDE_RATING,
    DELETE_RATING,
    WARN_AUTHOR,
    SUSPEND_AUTHOR;

    public boolean appliesTo(ReportTargetType targetType) {
        return switch (this) {
            case DISMISS, RESOLVE -> true;
            case WARN, SUSPEND_TARGET -> targetType == ReportTargetType.USER;
            case REMOVE_CONTENT -> targetType == ReportTargetType.ANNOUNCEMENT
                    || targetType == ReportTargetType.PACKAGE_REQUEST;
            case DELETE_MESSAGE -> targetType == ReportTargetType.MESSAGE;
            case EXCLUDE_RATING, DELETE_RATING -> targetType == ReportTargetType.RATING;
            case WARN_AUTHOR, SUSPEND_AUTHOR -> targetType == ReportTargetType.MESSAGE
                    || targetType == ReportTargetType.RATING
                    || targetType == ReportTargetType.BID;
        };
    }

    /** Statut du signalement après l'action : seul le rejet n'est pas une résolution. */
    public ReportStatus resultingStatus() {
        return this == DISMISS ? ReportStatus.DISMISSED : ReportStatus.RESOLVED;
    }
}
