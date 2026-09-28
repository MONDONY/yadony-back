package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserService;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementRemovalReason;
import com.yadony.api.matching.AnnouncementService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.service.PackageRequestModerationService;
import com.yadony.api.signalements.ReportAction;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportActionExecutorTest {

    @Mock UserService userService;
    @Mock AnnouncementService announcementService;
    @Mock PackageRequestModerationService packageRequestModerationService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock AdminMessageModerationService messageModeration;
    @Mock AdminRatingModerationService ratingModeration;

    private final UUID reportId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();

    private ReportActionExecutor executor() {
        return new ReportActionExecutor(userService, announcementService, packageRequestModerationService,
                notificationDispatcher, messageModeration, ratingModeration);
    }

    private ReportEntity report(ReportTargetType type) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(type);
        r.setTargetId(targetId);
        return r;
    }

    private ResolvedReportTarget withAuthor() {
        UserEntity author = new UserEntity();
        ReflectionTestUtils.setField(author, "id", authorId);
        return new ResolvedReportTarget(true, false, author, null, null);
    }

    @Test
    void resolve_aucunGeste() {
        executor().apply(reportId, ReportAction.RESOLVE, report(ReportTargetType.APP), ResolvedReportTarget.none(),
                "vu", adminId);
        verifyNoInteractions(userService, announcementService, packageRequestModerationService,
                notificationDispatcher, messageModeration, ratingModeration);
    }

    @Test
    void deleteMessage_delegueALaModerationDesMessages() {
        ConversationEntity conv = new ConversationEntity();
        var target = new ResolvedReportTarget(true, false, null, conv, "msg_1");

        executor().apply(reportId, ReportAction.DELETE_MESSAGE, report(ReportTargetType.MESSAGE), target, "insulte", adminId);

        verify(messageModeration).deleteMessage(conv, "msg_1", adminId);
    }

    @Test
    void excludeRating_delegueAvecLaNoteCommeMotif() {
        executor().apply(reportId, ReportAction.EXCLUDE_RATING, report(ReportTargetType.RATING), withAuthor(),
                "avis mensonger", adminId);

        verify(ratingModeration).exclude(targetId, true, "avis mensonger", adminId);
    }

    @Test
    void deleteRating_delegue() {
        executor().apply(reportId, ReportAction.DELETE_RATING, report(ReportTargetType.RATING), withAuthor(),
                "insultes", adminId);

        verify(ratingModeration).delete(targetId, "insultes", adminId);
    }

    @Test
    void warnAuthor_avertitLAuteurCommeWarn() {
        when(notificationDispatcher.messagesFor(authorId)).thenReturn(TestMessages.fr());

        executor().apply(reportId, ReportAction.WARN_AUTHOR, report(ReportTargetType.BID), withAuthor(), null, adminId);

        verify(notificationDispatcher).notifyUser(eq(authorId), eq("Avertissement Yadony"),
                eq("Un comportement signalé sur votre compte a été examiné."),
                eq(Map.of("type", "ADMIN_WARNING", "reportId", reportId.toString())));
    }

    @Test
    void suspendAuthor_suspendLAuteurAvecLAdmin() {
        executor().apply(reportId, ReportAction.SUSPEND_AUTHOR, report(ReportTargetType.MESSAGE), withAuthor(),
                "harcèlement", adminId);

        verify(userService).suspendUser(authorId, "harcèlement", adminId);
    }

    @Test
    void warn_avertitLaCible() {
        when(notificationDispatcher.messagesFor(targetId)).thenReturn(TestMessages.fr());

        executor().apply(reportId, ReportAction.WARN, report(ReportTargetType.USER), ResolvedReportTarget.none(),
                "Dernier avertissement", adminId);

        verify(notificationDispatcher).notifyUser(eq(targetId), eq("Avertissement Yadony"), eq("Dernier avertissement"),
                eq(Map.of("type", "ADMIN_WARNING", "reportId", reportId.toString())));
    }

    @Test
    void suspendTarget_suspendLaCible() {
        executor().apply(reportId, ReportAction.SUSPEND_TARGET, report(ReportTargetType.USER),
                ResolvedReportTarget.none(), "récidive", adminId);

        verify(userService).suspendUser(targetId, "récidive", adminId);
    }

    @Test
    void removeContent_annonceOuDemande() {
        executor().apply(reportId, ReportAction.REMOVE_CONTENT, report(ReportTargetType.ANNOUNCEMENT),
                ResolvedReportTarget.none(), "fraude", adminId);
        verify(announcementService).removeByAdmin(targetId, adminId, AnnouncementRemovalReason.OTHER, "fraude");

        executor().apply(reportId, ReportAction.REMOVE_CONTENT, report(ReportTargetType.PACKAGE_REQUEST),
                ResolvedReportTarget.none(), "fraude", adminId);
        verify(packageRequestModerationService).removeByAdmin(eq(targetId), eq(adminId),
                eq(AnnouncementRemovalReason.OTHER), any());
    }
}
