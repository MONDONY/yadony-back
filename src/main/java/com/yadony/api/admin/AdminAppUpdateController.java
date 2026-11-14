package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AppUpdateRequest;
import com.yadony.api.admin.dto.AppUpdateResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bandeau « Nouvelle version disponible » : lit et change {@code recommended_build} dans Firebase
 * Remote Config. Meme protection que les autres reglages de configuration : ADMIN avec
 * {@code CONFIG_MANAGE} (jamais le support). Chaque methode re-declare l'expression COMPLETE : une
 * {@code @PreAuthorize} de methode remplace celle de la classe.
 */
@RestController
@RequestMapping("/admin/app-update")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
public class AdminAppUpdateController {

    private final AppUpdateService service;

    public AdminAppUpdateController(AppUpdateService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @GetMapping
    public AppUpdateResponse get() {
        return service.read();
    }

    /** L'audit_log est ecrit par {@link AppUpdateService} (ancienne et nouvelle valeur, acteur). */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PutMapping
    public AppUpdateResponse update(@RequestBody AppUpdateRequest request, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "admin-principal-required",
                    "problem.admin.admin-principal-required");
        }
        return service.updateRecommendedBuild(request.recommendedBuild(), principal.adminId());
    }
}
