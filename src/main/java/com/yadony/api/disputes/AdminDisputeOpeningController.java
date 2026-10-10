package com.yadony.api.disputes;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.disputes.dto.AdminOpenDisputeRequest;
import com.yadony.api.disputes.dto.AdminOpenDisputeResponse;
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
 * Fiche colis admin : « Ouvrir un litige ». Réservé au super-admin ({@code ADMIN_MANAGE}) :
 * ouvrir un litige gèle l'argent d'un tiers.
 */
@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('ADMIN_MANAGE')")
public class AdminDisputeOpeningController {

    private final AdminDisputeOpeningService service;

    public AdminDisputeOpeningController(AdminDisputeOpeningService service) {
        this.service = service;
    }

    @PostMapping("/admin/bids/{id}/disputes")
    public ResponseEntity<AdminOpenDisputeResponse> open(@PathVariable UUID id,
                                                         @Valid @RequestBody AdminOpenDisputeRequest request,
                                                         Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.open(id, adminId, request));
    }
}
