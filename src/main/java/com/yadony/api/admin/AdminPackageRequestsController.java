package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminPackageRequestDetailResponse;
import com.yadony.api.admin.dto.AdminPackageRequestListItemResponse;
import com.yadony.api.admin.dto.RemovePackageRequestRequest;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.service.PackageRequestModerationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Modération des demandes d'envoi. Lecture avec BID_VIEW (comme les colis et les annonces,
 * SUPPORT l'a), retrait et restauration avec CONTENT_REMOVE (ADMIN/SUPER_ADMIN seulement,
 * SUPPORT ne l'a pas). L'annotation de méthode remplace celle de classe : chacune répète
 * {@code hasRole('ADMIN')}.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminPackageRequestsController {

    private final AdminPackageRequestQueryService queryService;
    private final PackageRequestModerationService moderationService;

    public AdminPackageRequestsController(AdminPackageRequestQueryService queryService,
                                          PackageRequestModerationService moderationService) {
        this.queryService = queryService;
        this.moderationService = moderationService;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('BID_VIEW')")
    @GetMapping("/admin/package-requests")
    public Page<AdminPackageRequestListItemResponse> list(
            @RequestParam(required = false) PackageRequestStatus status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "false") boolean reportedOnly,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.list(status, query, reportedOnly, from, to, page, size);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('BID_VIEW')")
    @GetMapping("/admin/package-requests/{id}")
    public AdminPackageRequestDetailResponse detail(@PathVariable UUID id) {
        return queryService.detail(id);
    }

    /** Répond la fiche à jour (même forme que le détail). */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONTENT_REMOVE')")
    @PostMapping("/admin/package-requests/{id}/remove")
    public AdminPackageRequestDetailResponse remove(@PathVariable UUID id,
                                                    @RequestBody @Valid RemovePackageRequestRequest request,
                                                    Authentication authentication) {
        moderationService.removeByAdmin(id, AdminPrincipal.requireAdminId(authentication),
                request.publicReason(), request.internalNote());
        return queryService.detail(id);
    }

    /** Répond la fiche à jour (même forme que le détail). */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONTENT_REMOVE')")
    @PostMapping("/admin/package-requests/{id}/restore")
    public AdminPackageRequestDetailResponse restore(@PathVariable UUID id, Authentication authentication) {
        moderationService.restoreByAdmin(id, AdminPrincipal.requireAdminId(authentication));
        return queryService.detail(id);
    }
}
