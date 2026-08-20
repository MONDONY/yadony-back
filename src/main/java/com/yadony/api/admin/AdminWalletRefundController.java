package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminWalletRefundRequestResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletRefundRequestService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * File des demandes de remboursement wallet ouvertes par un utilisateur bloqué en
 * suppression de compte par un solde non nul (cf. {@code WalletRefundRequestService}).
 * L'admin rembourse manuellement hors-app via le dashboard Stripe, puis résout ici :
 * la résolution débite le wallet à zéro et débloque la suppression côté utilisateur.
 */
@RestController
@RequestMapping("/admin/wallet-refund-requests")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWalletRefundController {

    private final WalletRefundRequestService walletRefundRequestService;

    public AdminWalletRefundController(WalletRefundRequestService walletRefundRequestService) {
        this.walletRefundRequestService = walletRefundRequestService;
    }

    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @GetMapping
    public ResponseEntity<Page<AdminWalletRefundRequestResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                walletRefundRequestService.listPending(PageRequest.of(page, size))
                        .map(AdminWalletRefundRequestResponse::from));
    }

    @PreAuthorize("hasAuthority('PAYMENT_REFUND')")
    @PostMapping("/{id}/resolve")
    public ResponseEntity<AdminWalletRefundRequestResponse> resolve(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(AdminWalletRefundRequestResponse.from(
                walletRefundRequestService.resolve(id, adminId(authentication))));
    }

    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
