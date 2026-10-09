package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Mode recette côté liste des utilisateurs de l'admin : disponibilité du mode dans
 * l'environnement et désignation des testeurs en masse.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminRecetteController {

    private final AdminRecetteTesterService service;

    public AdminRecetteController(AdminRecetteTesterService service) {
        this.service = service;
    }

    public record RecetteStatusResponse(boolean enabled) {}

    public record BulkRecetteTesterRequest(
            @NotEmpty(message = "Sélectionnez au moins un utilisateur")
            @Size(max = AdminRecetteTesterService.MAX_BULK,
                    message = "Au plus " + AdminRecetteTesterService.MAX_BULK + " utilisateurs par lot")
            List<@NotNull UUID> userIds,
            @NotNull Boolean enabled) {}

    /**
     * Le mode recette est-il ouvert ici (staging) ? Lecture seule, sans effet : l'admin s'en
     * sert pour afficher, ou non, les actions de recette sans tenter un PUT voué au 409.
     */
    @GetMapping("/admin/recette/status")
    public RecetteStatusResponse status() {
        return new RecetteStatusResponse(service.isAvailable());
    }

    @PutMapping("/admin/users/recette-tester")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('ADMIN_MANAGE')")
    public AdminRecetteTesterService.BulkResult setBulk(@Valid @RequestBody BulkRecetteTesterRequest request,
                                                        Authentication authentication) {
        return service.setBulk(request.userIds(), request.enabled(), adminId(authentication));
    }

    private static UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
