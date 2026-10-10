package com.yadony.api.cancellation;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.cancellation.dto.AdminBidCancelRequest;
import com.yadony.api.cancellation.dto.AdminBidCancelResponse;
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
 * Fiche colis admin : « Annuler le colis ». Vit dans {@code cancellation/} : la logique
 * d'annulation n'existe que dans ce package. Réservé au super-admin ({@code ADMIN_MANAGE}) :
 * le geste rembourse l'expéditeur et retire le transport au voyageur.
 */
@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('ADMIN_MANAGE')")
public class AdminBidCancellationController {

    private final AdminBidCancellationService service;

    public AdminBidCancellationController(AdminBidCancellationService service) {
        this.service = service;
    }

    @PostMapping("/admin/bids/{id}/cancel")
    public ResponseEntity<AdminBidCancelResponse> cancel(@PathVariable UUID id,
                                                         @Valid @RequestBody AdminBidCancelRequest request,
                                                         Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        return ResponseEntity.ok(service.cancel(id, adminId, request));
    }
}
