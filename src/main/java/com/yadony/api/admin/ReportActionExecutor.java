package com.yadony.api.admin;

import com.yadony.api.auth.UserService;
import com.yadony.api.matching.AnnouncementRemovalReason;
import com.yadony.api.matching.AnnouncementService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import com.yadony.api.requests.service.PackageRequestModerationService;
import com.yadony.api.signalements.ReportAction;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportTargetType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Exécute le geste d'une résolution de signalement en DÉLÉGUANT au service de modération qui
 * le porte déjà ailleurs dans l'admin ; rien n'est réimplémenté ici. Chaque service délégué
 * écrit son propre audit avec l'admin acteur (suspension, retrait, suppression de message,
 * exclusion ou suppression d'avis) ; l'audit {@code REPORT_RESOLVED} s'y ajoute.
 *
 * <p>Permissions et exécutabilité sont vérifiées AVANT par l'appelant
 * ({@link ReportActionPolicy}).
 */
@Component
public class ReportActionExecutor {

    private final UserService userService;
    private final AnnouncementService announcementService;
    private final PackageRequestModerationService packageRequestModerationService;
    private final NotificationDispatcher notificationDispatcher;
    private final AdminMessageModerationService messageModeration;
    private final AdminRatingModerationService ratingModeration;

    public ReportActionExecutor(UserService userService,
                                AnnouncementService announcementService,
                                PackageRequestModerationService packageRequestModerationService,
                                NotificationDispatcher notificationDispatcher,
                                AdminMessageModerationService messageModeration,
                                AdminRatingModerationService ratingModeration) {
        this.userService = userService;
        this.announcementService = announcementService;
        this.packageRequestModerationService = packageRequestModerationService;
        this.notificationDispatcher = notificationDispatcher;
        this.messageModeration = messageModeration;
        this.ratingModeration = ratingModeration;
    }

    public void apply(UUID reportId, ReportAction action, ReportEntity report, ResolvedReportTarget target,
                      String note, UUID adminId) {
        switch (action) {
            case DISMISS, RESOLVE -> { }
            case WARN -> warn(report.getTargetId(), reportId, note);
            case WARN_AUTHOR -> warn(target.author().getId(), reportId, note);
            case SUSPEND_TARGET -> userService.suspendUser(report.getTargetId(), note, adminId);
            case SUSPEND_AUTHOR -> userService.suspendUser(target.author().getId(), note, adminId);
            case REMOVE_CONTENT -> {
                if (report.getTargetType() == ReportTargetType.PACKAGE_REQUEST) {
                    packageRequestModerationService.removeByAdmin(report.getTargetId(), adminId,
                            AnnouncementRemovalReason.OTHER, note);
                } else {
                    announcementService.removeByAdmin(report.getTargetId(), adminId,
                            AnnouncementRemovalReason.OTHER, note);
                }
            }
            case DELETE_MESSAGE -> messageModeration.deleteMessage(target.conversation(), target.messageId(), adminId);
            case EXCLUDE_RATING -> ratingModeration.exclude(report.getTargetId(), true, note, adminId);
            case DELETE_RATING -> ratingModeration.delete(report.getTargetId(), note, adminId);
        }
    }

    /** Même avertissement que WARN : notification ADMIN_WARNING, la note de l'admin en corps. */
    private void warn(UUID userId, UUID reportId, String note) {
        var text = NotificationTexts.adminWarning(notificationDispatcher.messagesFor(userId), note);
        notificationDispatcher.notifyUser(userId, text.title(), text.body(),
                Map.of("type", "ADMIN_WARNING", "reportId", reportId.toString()));
    }
}
