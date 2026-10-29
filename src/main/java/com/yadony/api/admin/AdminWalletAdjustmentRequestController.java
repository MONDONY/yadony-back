package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminWalletAccountResponse;
import com.yadony.api.admin.dto.AdminWalletAdjustmentRequestResponse;
import com.yadony.api.admin.dto.AdminWalletAdjustmentResponse;
import com.yadony.api.admin.dto.AdminWalletTransactionResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletAccountView;
import com.yadony.api.payments.wallet.WalletAdjustmentRequestEntity;
import com.yadony.api.payments.wallet.WalletAdjustmentRequestStatus;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentResult;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * File d'approbation des corrections de solde au-delà du seuil de double validation : un second
 * admin, jamais l'auteur, approuve (le mouvement est alors écrit) ou rejette.
 *
 * <p>Une {@code @PreAuthorize} de méthode remplace celle de la classe : chaque méthode répète
 * {@code hasRole('ADMIN')}.
 */
@RestController
@RequestMapping("/admin/wallet-adjustment-requests")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWalletAdjustmentRequestController {

    /** Note facultative d'un rejet. */
    public record RejectRequest(String note) {}

    private final WalletAdminAdjustmentService adjustmentService;
    private final AdminUserRepository adminUserRepository;

    public AdminWalletAdjustmentRequestController(WalletAdminAdjustmentService adjustmentService,
                                                  AdminUserRepository adminUserRepository) {
        this.adjustmentService = adjustmentService;
        this.adminUserRepository = adminUserRepository;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('WALLET_ADJUST')")
    @GetMapping
    public Page<AdminWalletAdjustmentRequestResponse> list(@RequestParam(defaultValue = "PENDING") String status,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        Page<WalletAdjustmentRequestEntity> requests = adjustmentService.requests(parseStatus(status), page, size);
        Map<UUID, String> emails = emails(requests.getContent());
        return requests.map(r -> AdminWalletAdjustmentRequestResponse.from(r, emails.get(r.getRequestedBy()),
                emails.get(r.getDecidedBy())));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('WALLET_ADJUST')")
    @PostMapping("/{requestId}/approve")
    public AdminWalletAdjustmentResponse approve(@PathVariable UUID requestId, Authentication authentication) {
        AdjustmentResult result = adjustmentService.approve(requestId, AdminPrincipal.requireAdminId(authentication));
        WalletTransactionEntity tx = result.transaction();
        WalletAdjustmentRequestEntity request = result.pendingRequest();
        Map<UUID, String> emails = emails(List.of(request));
        WalletAccountView account = adjustmentService.account(tx.getUserId(), tx.getCurrency());
        return new AdminWalletAdjustmentResponse(
                account == null ? null : AdminWalletAccountResponse.from(account),
                AdminWalletTransactionResponse.from(tx, emails.get(tx.getAdminActorId())),
                AdminWalletAdjustmentRequestResponse.from(request, emails.get(request.getRequestedBy()),
                        emails.get(request.getDecidedBy())));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('WALLET_ADJUST')")
    @PostMapping("/{requestId}/reject")
    public AdminWalletAdjustmentRequestResponse reject(@PathVariable UUID requestId,
                                                       @RequestBody(required = false) RejectRequest body,
                                                       Authentication authentication) {
        WalletAdjustmentRequestEntity request = adjustmentService.reject(requestId,
                AdminPrincipal.requireAdminId(authentication), body == null ? null : body.note());
        Map<UUID, String> emails = emails(List.of(request));
        return AdminWalletAdjustmentRequestResponse.from(request, emails.get(request.getRequestedBy()),
                emails.get(request.getDecidedBy()));
    }

    private static WalletAdjustmentRequestStatus parseStatus(String status) {
        try {
            return WalletAdjustmentRequestStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "wallet-adjustment-request-status-invalid",
                    "Bad Request", "Statut inconnu : " + status.trim());
        }
    }

    /** E-mail des auteurs et décideurs, en une seule lecture. */
    private Map<UUID, String> emails(List<WalletAdjustmentRequestEntity> requests) {
        List<UUID> ids = requests.stream()
                .flatMap(r -> Stream.of(r.getRequestedBy(), r.getDecidedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, String> emails = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AdminUserEntity admin : adminUserRepository.findAllById(ids)) {
                emails.put(admin.getId(), admin.getEmail());
            }
        }
        return emails;
    }
}
