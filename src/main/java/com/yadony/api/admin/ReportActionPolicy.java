package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.signalements.ReportAction;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Qui peut quoi sur un signalement, et quand : permissions par action, actions proposées à
 * l'admin appelant, exécutabilité d'un appel direct.
 *
 * <p>Toutes les actions exigent {@code REPORT_RESOLVE} (porté par {@code @PreAuthorize} sur
 * l'endpoint de résolution). Un geste qui touche autre chose que le signalement exige EN PLUS
 * la permission de ce geste : un support qui traite des signalements ne doit pas pouvoir
 * supprimer un avis ou suspendre un compte par ce détour.
 */
public final class ReportActionPolicy {

    /** Ordre d'affichage des actions proposées : clore d'abord, puis sanctionner, puis retirer. */
    private static final List<ReportAction> DISPLAY_ORDER = List.of(
            ReportAction.RESOLVE, ReportAction.DISMISS,
            ReportAction.WARN, ReportAction.SUSPEND_TARGET,
            ReportAction.WARN_AUTHOR, ReportAction.SUSPEND_AUTHOR,
            ReportAction.REMOVE_CONTENT, ReportAction.DELETE_MESSAGE,
            ReportAction.EXCLUDE_RATING, ReportAction.DELETE_RATING);

    private ReportActionPolicy() {}

    /** Permission propre au geste, en plus de REPORT_RESOLVE ; {@code null} si aucune. */
    static AdminPermission extraPermission(ReportAction action) {
        return switch (action) {
            case RESOLVE, DISMISS, WARN, WARN_AUTHOR -> null;
            case SUSPEND_TARGET, SUSPEND_AUTHOR -> AdminPermission.USER_SUSPEND;
            case REMOVE_CONTENT -> AdminPermission.CONTENT_REMOVE;
            case DELETE_MESSAGE -> AdminPermission.MESSAGE_DELETE;
            case EXCLUDE_RATING -> AdminPermission.RATING_MODERATE;
            case DELETE_RATING -> AdminPermission.RATING_DELETE;
        };
    }

    public static Set<String> requiredAuthorities(ReportAction action) {
        Set<String> required = new LinkedHashSet<>();
        required.add(AdminPermission.REPORT_RESOLVE.name());
        AdminPermission extra = extraPermission(action);
        if (extra != null) {
            required.add(extra.name());
        }
        return required;
    }

    /** 403 {@code authority-required} si la permission propre au geste manque. */
    public static void requireAuthorities(ReportAction action, Authentication authentication) {
        AdminPermission extra = extraPermission(action);
        if (extra != null && !authorities(authentication).contains(extra.name())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "authority-required",
                    "Forbidden", "Permission " + extra.name() + " requise pour cette action");
        }
    }

    public static Set<String> authorities(Authentication authentication) {
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /**
     * Un appel direct peut-il agir sur cette cible ? Non : 422 {@code report-target-unresolvable}.
     *
     * <p>Les actions historiques (WARN, SUSPEND_TARGET, REMOVE_CONTENT) gardent leur
     * comportement d'avant : leurs services délégués répondent eux-mêmes 404.
     */
    public static boolean isExecutable(ReportAction action, ResolvedReportTarget target) {
        return switch (action) {
            case RESOLVE, DISMISS, WARN, SUSPEND_TARGET, REMOVE_CONTENT -> true;
            case DELETE_MESSAGE -> target.messageResolved();
            case EXCLUDE_RATING, DELETE_RATING -> target.targetFound();
            case WARN_AUTHOR, SUSPEND_AUTHOR -> target.author() != null;
        };
    }

    /** L'action a-t-elle un sens sur la cible telle qu'elle est aujourd'hui ? */
    static boolean isRelevant(ReportAction action, ResolvedReportTarget target) {
        return switch (action) {
            case RESOLVE, DISMISS -> true;
            case WARN, SUSPEND_TARGET -> target.author() != null;
            case REMOVE_CONTENT -> target.targetFound();
            case DELETE_MESSAGE -> target.messageResolved() && !target.alreadyModerated();
            case EXCLUDE_RATING -> target.targetFound() && !target.alreadyModerated();
            case DELETE_RATING -> target.targetFound();
            case WARN_AUTHOR, SUSPEND_AUTHOR -> target.author() != null;
        };
    }

    /**
     * Actions proposées à l'admin appelant pour ce signalement : type de cible × cible
     * retrouvée × permissions. Vide si le signalement est déjà traité (ou rejeté).
     */
    public static List<ReportAction> availableActions(ReportEntity report, ResolvedReportTarget target,
                                                      Collection<String> authorities) {
        if (report.getStatus() != ReportStatus.OPEN || report.getTargetType() == null
                || !authorities.contains(AdminPermission.REPORT_RESOLVE.name())) {
            return List.of();
        }
        ResolvedReportTarget t = target != null ? target : ResolvedReportTarget.none();
        List<ReportAction> actions = new ArrayList<>();
        for (ReportAction action : DISPLAY_ORDER) {
            if (action.appliesTo(report.getTargetType())
                    && authorities.containsAll(requiredAuthorities(action))
                    && isRelevant(action, t)) {
                actions.add(action);
            }
        }
        return actions;
    }
}
