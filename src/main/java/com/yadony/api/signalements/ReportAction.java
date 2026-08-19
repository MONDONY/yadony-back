package com.yadony.api.signalements;

/**
 * Actions possibles à la résolution d'un signalement (voir AdminReportsController.resolveReport).
 *
 * <p>{@code SUSPEND_TARGET} et {@code REMOVE_CONTENT} délèguent à des services de modération
 * déjà existants (ils ne réimplémentent rien) : le premier n'a de sens que pour une cible
 * {@code USER}, le second que pour une cible {@code ANNOUNCEMENT}. Toute autre combinaison
 * est refusée par le contrôleur — voir {@code appliesTo}.
 */
public enum ReportAction {
    DISMISS,
    WARN,
    SUSPEND_TARGET,
    REMOVE_CONTENT;

    public boolean appliesTo(ReportTargetType targetType) {
        return switch (this) {
            case DISMISS -> true;
            case WARN -> targetType == ReportTargetType.USER;
            case SUSPEND_TARGET -> targetType == ReportTargetType.USER;
            case REMOVE_CONTENT -> targetType == ReportTargetType.ANNOUNCEMENT;
        };
    }
}
