package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminUserWalletResponse;
import com.yadony.api.admin.dto.AdminWalletAccountResponse;
import com.yadony.api.admin.dto.AdminWalletAdjustmentRequest;
import com.yadony.api.admin.dto.AdminWalletAdjustmentResponse;
import com.yadony.api.admin.dto.AdminWalletTransactionResponse;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletAccountView;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentCommand;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Portefeuilles d'un utilisateur depuis sa fiche admin : soldes, journal des mouvements et
 * correction manuelle du solde (cf. {@link WalletAdminAdjustmentService}).
 *
 * <p>Une {@code @PreAuthorize} de méthode remplace celle de la classe : chaque méthode répète
 * donc {@code hasRole('ADMIN')}.
 */
@RestController
@RequestMapping("/admin/users/{userId}/wallet")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserWalletController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final WalletAdminAdjustmentService adjustmentService;
    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;

    public AdminUserWalletController(WalletAdminAdjustmentService adjustmentService,
                                     UserRepository userRepository,
                                     AdminUserRepository adminUserRepository) {
        this.adjustmentService = adjustmentService;
        this.userRepository = userRepository;
        this.adminUserRepository = adminUserRepository;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping
    public AdminUserWalletResponse wallet(@PathVariable UUID userId) {
        requireUser(userId);
        return new AdminUserWalletResponse(adjustmentService.accounts(userId).stream()
                .map(AdminWalletAccountResponse::from)
                .toList());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/transactions")
    public Page<AdminWalletTransactionResponse> transactions(@PathVariable UUID userId,
                                                             @RequestParam(required = false) String currency,
                                                             @RequestParam(required = false) String type,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        requireUser(userId);
        Page<WalletTransactionEntity> transactions = adjustmentService.transactions(userId, currency, type, page, size);
        Map<UUID, String> emails = adminEmails(transactions.getContent());
        return transactions.map(tx -> AdminWalletTransactionResponse.from(tx, emails.get(tx.getAdminActorId())));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('WALLET_ADJUST')")
    @PostMapping("/adjustments")
    public AdminWalletAdjustmentResponse adjust(@PathVariable UUID userId,
                                                @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
                                                String idempotencyKey,
                                                @RequestBody AdminWalletAdjustmentRequest request,
                                                Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "idempotency-key-required", "Bad Request",
                    "L'en-tête " + IDEMPOTENCY_HEADER + " est obligatoire");
        }
        requireUser(userId);

        AdjustmentResult result = adjustmentService.adjust(new AdjustmentCommand(userId, adminId,
                request.currency(), request.direction(), request.amount(), request.reason(), idempotencyKey));
        WalletTransactionEntity tx = result.transaction();
        WalletAccountView account = adjustmentService.account(userId, tx.getCurrency());
        return new AdminWalletAdjustmentResponse(
                account == null ? null : AdminWalletAccountResponse.from(account),
                AdminWalletTransactionResponse.from(tx, adminEmails(List.of(tx)).get(tx.getAdminActorId())));
    }

    /** Utilisateur inexistant ou supprimé (soft delete) : 404, avant toute lecture du wallet. */
    private void requireUser(UUID userId) {
        userRepository.findById(userId)
                .filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "Not Found", "Utilisateur introuvable"));
    }

    /** E-mail des admins auteurs d'une correction, en une seule lecture pour la page. */
    private Map<UUID, String> adminEmails(Collection<WalletTransactionEntity> transactions) {
        List<UUID> ids = transactions.stream()
                .map(WalletTransactionEntity::getAdminActorId)
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
