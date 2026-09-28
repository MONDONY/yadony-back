package com.yadony.api.cancellation;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.cancellation.dto.AdminNoShowDecisionRequest;
import com.yadony.api.cancellation.dto.AdminNoShowResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
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
 * Écran admin Incidents > No-shows : file des déclarations et arbitrage.
 * Vit dans {@code cancellation/} (et non {@code admin/}) : la logique d'annulation
 * n'existe que dans ce package.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminNoShowController {

    static final String DISPUTE_RESOLVE = "DISPUTE_RESOLVE";

    private final AdminNoShowQueryService queryService;
    private final NoShowArbitrationService arbitrationService;

    public AdminNoShowController(AdminNoShowQueryService queryService,
                                 NoShowArbitrationService arbitrationService) {
        this.queryService = queryService;
        this.arbitrationService = arbitrationService;
    }

    /**
     * @param noShowStatus ancien nom du filtre de statut, lu si {@code status} est absent
     */
    @GetMapping("/admin/cancellations")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DISPUTE_VIEW')")
    public ResponseEntity<Page<AdminNoShowResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String noShowStatus,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        String effectiveStatus = status != null ? status : noShowStatus;
        return ResponseEntity.ok(queryService.list(effectiveStatus, scope, page, size, canResolve(authentication)));
    }

    @PostMapping("/admin/cancellations/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DISPUTE_RESOLVE')")
    public ResponseEntity<AdminNoShowResponse> confirm(@PathVariable UUID id,
                                                       @Valid @RequestBody AdminNoShowDecisionRequest request,
                                                       Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        CancellationEntity decided = arbitrationService.confirm(id, adminId, request.reason());
        return ResponseEntity.ok(queryService.describe(decided, true));
    }

    @PostMapping("/admin/cancellations/{id}/reject")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DISPUTE_RESOLVE')")
    public ResponseEntity<AdminNoShowResponse> reject(@PathVariable UUID id,
                                                      @Valid @RequestBody AdminNoShowDecisionRequest request,
                                                      Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        CancellationEntity decided = arbitrationService.reject(id, adminId, request.reason());
        return ResponseEntity.ok(queryService.describe(decided, true));
    }

    private static boolean canResolve(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> DISPUTE_RESOLVE.equals(a.getAuthority()));
    }
}
