package com.yadony.api.tracking;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.tracking.dto.AdminBidStatusChangeRequest;
import com.yadony.api.tracking.dto.AdminBidStatusChangeResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Fiche colis admin : « Changer le statut ». Réservé au super-admin ({@code ADMIN_MANAGE}) :
 * passer un colis à « livré » libère le versement au voyageur.
 */
@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('ADMIN_MANAGE')")
public class AdminBidStatusController {

    private final AdminBidStatusService service;

    public AdminBidStatusController(AdminBidStatusService service) {
        this.service = service;
    }

    @PostMapping("/admin/bids/{id}/status")
    public ResponseEntity<AdminBidStatusChangeResponse> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AdminBidStatusChangeRequest request,
            Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        return ResponseEntity.ok(service.changeStatus(id, adminId, request));
    }
}
