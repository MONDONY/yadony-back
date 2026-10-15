package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminTopupCodeRedemptionResponse;
import com.yadony.api.admin.dto.AdminTopupCodeResponse;
import com.yadony.api.admin.dto.CreateTopupCodeRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeAdminService;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeEntity;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeRedemptionEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Codes de recharge du solde. Une @PreAuthorize de méthode remplace celle de la classe :
 * chaque méthode répète donc hasRole('ADMIN').
 */
@RestController
@RequestMapping("/admin/topup-codes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTopupCodeController {

    private final WalletTopupCodeAdminService service;
    private final AdminUserRepository adminUserRepository;
    private final UserRepository userRepository;

    public AdminTopupCodeController(WalletTopupCodeAdminService service,
                                    AdminUserRepository adminUserRepository,
                                    UserRepository userRepository) {
        this.service = service;
        this.adminUserRepository = adminUserRepository;
        this.userRepository = userRepository;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('TOPUP_CODE_MANAGE')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminTopupCodeResponse create(@Valid @RequestBody CreateTopupCodeRequest request,
                                         Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        WalletTopupCodeEntity created = service.create(new WalletTopupCodeAdminService.CreateCommand(
                request.code(), request.amount(), request.currency(), request.maxRedemptions(),
                request.expiresAt(), request.note(), adminId));
        return toResponses(List.of(created)).get(0);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('TOPUP_CODE_MANAGE')")
    @GetMapping
    public List<AdminTopupCodeResponse> list() {
        return toResponses(service.list());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('TOPUP_CODE_MANAGE')")
    @GetMapping("/{id}")
    public AdminTopupCodeResponse get(@PathVariable UUID id) {
        return toResponses(List.of(service.get(id))).get(0);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('TOPUP_CODE_MANAGE')")
    @GetMapping("/{id}/redemptions")
    public List<AdminTopupCodeRedemptionResponse> redemptions(@PathVariable UUID id) {
        List<WalletTopupCodeRedemptionEntity> rows = service.redemptions(id);
        Map<UUID, UserEntity> users = userRepository.findAllById(
                        rows.stream().map(WalletTopupCodeRedemptionEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        return rows.stream().map(r -> new AdminTopupCodeRedemptionResponse(r.getId(), r.getUserId(),
                displayName(users.get(r.getUserId())), r.getAmount(), r.getCurrency(), r.getCreatedAt())).toList();
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('TOPUP_CODE_MANAGE')")
    @PostMapping("/{id}/revoke")
    public AdminTopupCodeResponse revoke(@PathVariable UUID id, Authentication authentication) {
        return toResponses(List.of(service.revoke(id, AdminPrincipal.requireAdminId(authentication)))).get(0);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('TOPUP_CODE_MANAGE')")
    @PostMapping("/generate-code")
    public Map<String, String> generateCode() {
        return Map.of("code", service.generateCode());
    }

    private List<AdminTopupCodeResponse> toResponses(List<WalletTopupCodeEntity> codes) {
        Map<UUID, String> emails = adminUserRepository.findAllById(
                        codes.stream().map(WalletTopupCodeEntity::getCreatedBy).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(a -> a.getId(), a -> a.getEmail()));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return codes.stream().map(c -> new AdminTopupCodeResponse(c.getId(), c.getCode(), c.getAmount(),
                c.getCurrency(), c.getMaxRedemptions(), c.getRedeemedCount(), c.remaining(), c.getExpiresAt(),
                c.displayStatus(now).name(), c.getNote(), c.getCreatedAt(), c.getCreatedBy(),
                emails.get(c.getCreatedBy()), c.getRevokedAt())).toList();
    }

    private static String displayName(UserEntity user) {
        if (user == null) return null;
        String full = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
