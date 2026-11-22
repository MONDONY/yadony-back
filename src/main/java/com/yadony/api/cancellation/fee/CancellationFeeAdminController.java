package com.yadony.api.cancellation.fee;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.cancellation.dto.AdminCancellationFeeActionRequest;
import com.yadony.api.cancellation.dto.AdminCancellationFeeResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
 *
 * <p>Tout compte admin porte {@code ROLE_ADMIN} (SUPPORT compris) : chaque geste exige en plus la permission du
 * mouvement d'argent qu'il ouvre, comme les autres gestes admin d'argent.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class CancellationFeeAdminController {

    private final CancellationFeeAdminService service;

    public CancellationFeeAdminController(CancellationFeeAdminService service) {
        this.service = service;
    }

    /**
     * Lecture de la retenue d'un colis, pour la carte « Frais d'annulation » de la fiche du paiement : PAYMENT_VIEW
     * comme les autres lectures de paiement (le SUPPORT la garde) ; PAYMENT_REFUND et PAYMENT_RELEASE restent sur les
     * gestes.
     */
    @GetMapping("/admin/cancellation-fees/bids/{bidId}")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<AdminCancellationFeeResponse> read(@PathVariable UUID bidId) {
        return ResponseEntity.ok(service.describeByBid(bidId));
    }

    /** Rend tout à l'expéditeur au remboursement suivant : même permission que « Rembourser » (PAYMENT_REFUND). */
    @PostMapping("/admin/cancellation-fees/bids/{bidId}/waive")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_REFUND')")
    public ResponseEntity<AdminCancellationFeeResponse> waive(@PathVariable UUID bidId,
                                                              @Valid @RequestBody AdminCancellationFeeActionRequest request,
                                                              Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        return ResponseEntity.ok(service.waive(bidId, adminId, request.reason()));
    }

    /**
     * Déclenche un versement : même permission que la relance d'un versement de colis (PAYMENT_RELEASE). La tentative
     * a lieu après le commit du réarmement, avant la réponse : celle-ci dit l'état de la ligne à son issue
     * ({@code PAID_OUT}, ou toujours {@code PAYOUT_PENDING} avec le nouveau motif, ou en vol chez pawaPay).
     */
    @PostMapping("/admin/cancellation-fees/{feeId}/retry-payout")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_RELEASE')")
    public ResponseEntity<AdminCancellationFeeResponse> retryPayout(@PathVariable UUID feeId,
                                                                    @Valid @RequestBody AdminCancellationFeeActionRequest request,
                                                                    Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        service.retryPayout(feeId, adminId, request.reason());
        return ResponseEntity.ok(service.describe(feeId));
    }
}
