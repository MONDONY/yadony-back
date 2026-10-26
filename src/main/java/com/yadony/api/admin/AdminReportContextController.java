package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminReportContextResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Contexte d'un signalement ouvert dans le poste de traitement (consultation seule). */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportContextController {

    private final ReportRepository reportRepo;
    private final ReportContextService contextService;

    public AdminReportContextController(ReportRepository reportRepo, ReportContextService contextService) {
        this.reportRepo = reportRepo;
        this.contextService = contextService;
    }

    /**
     * Les messages qui entourent un message signalé sont de la conversation de deux personnes :
     * ils ne sont rendus qu'avec {@code MODERATION_VIEW}, le droit qui ouvre déjà ces conversations.
     */
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @GetMapping("/admin/reports/{id}/context")
    public ResponseEntity<AdminReportContextResponse> context(@PathVariable UUID id, Authentication authentication) {
        ReportEntity report = reportRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "report-not-found", "Not Found", "Signalement introuvable"));
        boolean canReadConversations = ReportActionPolicy.authorities(authentication).contains("MODERATION_VIEW");
        return ResponseEntity.ok(contextService.build(report, canReadConversations));
    }
}
