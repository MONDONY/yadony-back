package com.yadony.api.gppro;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.gppro.dto.AdminGpProApplicationDetail;
import com.yadony.api.gppro.dto.AdminGpProApplicationRow;
import com.yadony.api.gppro.dto.GpProDecisionRequest;
import com.yadony.api.gppro.dto.GpProGrantRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Badge GP Pro côté administration. Chaque méthode re-déclare {@code hasRole('ADMIN')} : une
 * {@code @PreAuthorize} de méthode remplace celle de la classe, elle ne s'y ajoute pas.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGpProController {

    private final GpProAdminService service;

    public AdminGpProController(GpProAdminService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @GetMapping("/gp-pro/applications")
    public Page<AdminGpProApplicationRow> list(
            @RequestParam(defaultValue = "PENDING") GpProApplicationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @GetMapping("/gp-pro/applications/{id}")
    public AdminGpProApplicationDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @PostMapping("/gp-pro/applications/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID id, Authentication authentication) {
        service.approve(id, AdminPrincipal.requireAdminId(authentication));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @PostMapping("/gp-pro/applications/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID id, @RequestBody @Valid GpProDecisionRequest request,
                       Authentication authentication) {
        service.reject(id, AdminPrincipal.requireAdminId(authentication), request.reason());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @PostMapping("/users/{userId}/gp-pro/grant")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@PathVariable UUID userId, @RequestBody @Valid GpProGrantRequest request,
                      Authentication authentication) {
        service.grant(userId, AdminPrincipal.requireAdminId(authentication), request.note());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @PostMapping("/users/{userId}/gp-pro/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID userId, @RequestBody @Valid GpProDecisionRequest request,
                       Authentication authentication) {
        service.revoke(userId, AdminPrincipal.requireAdminId(authentication), request.reason());
    }
}
