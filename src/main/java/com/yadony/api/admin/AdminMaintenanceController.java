package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminMaintenanceExemptionRequest;
import com.yadony.api.admin.dto.AdminMaintenanceResponse;
import com.yadony.api.admin.dto.AdminMaintenanceUpdateRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.maintenance.MaintenanceExemptionEntity;
import com.yadony.api.maintenance.MaintenanceService;
import com.yadony.api.maintenance.MaintenanceStateEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mode maintenance : interrupteur, message, heure de fin estimée et comptes exemptés.
 * Même permission que les paramètres plateforme ({@code CONFIG_MANAGE}), jamais accordée
 * au support. Une @PreAuthorize de méthode remplace celle de la classe : chaque méthode
 * répète donc hasRole('ADMIN').
 */
@RestController
@RequestMapping("/admin/maintenance")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMaintenanceController {

    private final MaintenanceService service;
    private final AdminUserRepository adminUserRepository;
    private final UserRepository userRepository;

    public AdminMaintenanceController(MaintenanceService service,
                                      AdminUserRepository adminUserRepository,
                                      UserRepository userRepository) {
        this.service = service;
        this.adminUserRepository = adminUserRepository;
        this.userRepository = userRepository;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @GetMapping
    public AdminMaintenanceResponse get() {
        return response();
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PutMapping
    public AdminMaintenanceResponse update(@Valid @RequestBody AdminMaintenanceUpdateRequest request,
                                           Authentication authentication) {
        service.update(new MaintenanceService.UpdateCommand(request.enabled(), request.message(),
                request.estimatedEndAt()), AdminPrincipal.requireAdminId(authentication));
        return response();
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PostMapping("/exemptions")
    public AdminMaintenanceResponse addExemption(@Valid @RequestBody AdminMaintenanceExemptionRequest request,
                                                 Authentication authentication) {
        if (!userRepository.existsById(request.userId())) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "Not Found",
                    "Utilisateur introuvable");
        }
        service.addExemption(request.userId(), request.note(), AdminPrincipal.requireAdminId(authentication));
        return response();
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @DeleteMapping("/exemptions/{userId}")
    public AdminMaintenanceResponse removeExemption(@PathVariable UUID userId, Authentication authentication) {
        service.removeExemption(userId, AdminPrincipal.requireAdminId(authentication));
        return response();
    }

    private AdminMaintenanceResponse response() {
        MaintenanceStateEntity state = service.state();
        List<MaintenanceExemptionEntity> exemptions = service.exemptions();
        Map<UUID, String> emails = adminUserRepository.findAllById(
                        Stream.concat(Stream.of(state.getUpdatedBy()),
                                        exemptions.stream().map(MaintenanceExemptionEntity::getCreatedBy))
                                .filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(a -> a.getId(), a -> a.getEmail()));
        Map<UUID, UserEntity> users = userRepository.findAllById(
                        exemptions.stream().map(MaintenanceExemptionEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        return new AdminMaintenanceResponse(state.isEnabled(), state.getMessage(),
                utc(state.getEstimatedEndAt()), utc(state.getStartedAt()), utc(state.getUpdatedAt()),
                emails.get(state.getUpdatedBy()),
                exemptions.stream().map(e -> new AdminMaintenanceResponse.Exemption(e.getUserId(),
                        displayName(users.get(e.getUserId())), e.getNote(), utc(e.getCreatedAt()),
                        emails.get(e.getCreatedBy()))).toList());
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String displayName(UserEntity user) {
        if (user == null) return null;
        String full = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
