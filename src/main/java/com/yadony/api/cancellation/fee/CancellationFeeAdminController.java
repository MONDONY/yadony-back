package com.yadony.api.cancellation.fee;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.cancellation.dto.AdminCancellationFeeActionRequest;
import com.yadony.api.cancellation.dto.AdminCancellationFeeResponse;
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
 * Gestes de l'administration sur une retenue d'annulation. Vit dans {@code cancellation/} : la retenue en est la
 * propriété. Aucun ne déplace d'argent par lui-même.
 * <ul>
 *   <li><b>Renoncer</b> à une retenue encore due : la ligne est close, les chemins de remboursement existants (écouteur
 *       rejoué, « Rembourser », relance d'un remboursement) rendent ensuite tout à l'expéditeur, carte comme mobile
 *       money. Pour un remboursement partiel que pawaPay refuse durablement, ou une retenue contestée.</li>
 *   <li><b>Relancer</b> un versement en attente : le plafond d'essais repart de zéro et le versement est tenté aussitôt,
 *       par le chemin habituel et toutes ses gardes.</li>
 * </ul>
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class CancellationFeeAdminController {

    private final CancellationFeeAdminService service;

    public CancellationFeeAdminController(CancellationFeeAdminService service) {
        this.service = service;
    }

    @PostMapping("/admin/cancellation-fees/bids/{bidId}/waive")
    public ResponseEntity<AdminCancellationFeeResponse> waive(@PathVariable UUID bidId,
                                                              @Valid @RequestBody AdminCancellationFeeActionRequest request,
                                                              Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        return ResponseEntity.ok(service.waive(bidId, adminId, request.reason()));
    }

    /**
     * La tentative a lieu après le commit du réarmement, avant la réponse : celle-ci dit l'état de la ligne à son issue
     * ({@code PAID_OUT}, ou toujours {@code PAYOUT_PENDING} avec le nouveau motif, ou en vol chez pawaPay).
     */
    @PostMapping("/admin/cancellation-fees/{feeId}/retry-payout")
    public ResponseEntity<AdminCancellationFeeResponse> retryPayout(@PathVariable UUID feeId,
                                                                    @Valid @RequestBody AdminCancellationFeeActionRequest request,
                                                                    Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        service.retryPayout(feeId, adminId, request.reason());
        return ResponseEntity.ok(service.describe(feeId));
    }
}
