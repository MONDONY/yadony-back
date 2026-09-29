package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminReportReplyRequest;
import com.yadony.api.admin.dto.AdminReportReplyResponse;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin › Signalements › « Répondre » sur un rapport de bug de l'app : la réponse part
 * dans une conversation « Yadony Support » avec le signalant (voir
 * {@link AdminReportReplyService}). 201 quand la conversation vient d'être ouverte, 200
 * quand la réponse s'ajoute au ticket déjà lié. Le signalement garde son statut.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportReplyController {

    private final AdminReportReplyService replyService;
    private final AdminSupportTicketViews views;

    public AdminReportReplyController(AdminReportReplyService replyService, AdminSupportTicketViews views) {
        this.replyService = replyService;
        this.views = views;
    }

    @PostMapping("/admin/reports/{id}/reply")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('REPORT_VIEW') and hasAuthority('SUPPORT_TICKET_MANAGE')")
    public ResponseEntity<AdminReportReplyResponse> reply(@PathVariable UUID id,
                                                          @Valid @RequestBody AdminReportReplyRequest request,
                                                          Authentication authentication) {
        AdminReportReplyService.Result result = replyService.reply(
                id, adminId(authentication), request.message(), request.attachmentKeys());
        AdminReportReplyResponse body = new AdminReportReplyResponse(
                result.ticket().getId(), result.created(), views.detail(result.ticket()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    private static UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
