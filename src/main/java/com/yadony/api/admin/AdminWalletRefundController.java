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

import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Remboursements wallet vus par l'admin. Sans filtre : la file « à traiter » (PENDING, tickets
 * manuels). Avec {@code status} : suivi des remboursements automatiques (PROCESSING), des échecs
 * (FAILED) et historique (REFUNDED, RESOLVED). Un ticket manuel se résout après remboursement
 * hors-app ; un remboursement automatique ne demande aucun geste.
 */
@RestController
@RequestMapping("/admin/wallet-refund-requests")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWalletRefundController {

    private final WalletRefundRequestService walletRefundRequestService;
    private final AdminWalletRefundListing listing;

    public AdminWalletRefundController(WalletRefundRequestService walletRefundRequestService,
                                       AdminWalletRefundListing listing) {
        this.walletRefundRequestService = walletRefundRequestService;
        this.listing = listing;
    }

    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @GetMapping
    public ResponseEntity<Page<AdminWalletRefundRequestResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(required = false) String query) {
        PageRequest pageable = PageRequest.of(page, size);
        UUID queryId = AdminSearchTerms.uuid(query);
        String like = queryId == null ? AdminSearchTerms.like(query) : null;
        if (queryId != null || like != null) {
            EnumSet<WalletRefundRequestStatus> scope = statuses == null || statuses.isEmpty()
                    ? EnumSet.allOf(WalletRefundRequestStatus.class) : parseStatuses(statuses);
            return ResponseEntity.ok(listing.describe(
                    walletRefundRequestService.search(scope, queryId, like, pageable)));
        }
        Page<WalletRefundRequestEntity> requests = statuses == null || statuses.isEmpty()
                ? walletRefundRequestService.listPending(pageable)
                : walletRefundRequestService.listByStatuses(parseStatuses(statuses), pageable);
        return ResponseEntity.ok(listing.describe(requests));
    }

    private static EnumSet<WalletRefundRequestStatus> parseStatuses(List<String> raw) {
        EnumSet<WalletRefundRequestStatus> statuses = EnumSet.noneOf(WalletRefundRequestStatus.class);
        for (String value : raw) {
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    statuses.add(WalletRefundRequestStatus.valueOf(part.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                            "wallet-refund-status-invalid", "Invalid Status",
                            "Statut de remboursement inconnu : " + part.trim());
                }
            }
        }
        if (statuses.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                    "wallet-refund-status-invalid", "Invalid Status", "Aucun statut demandé");
        }
        return statuses;
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
