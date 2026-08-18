package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.KycResetRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.KycAdminService;
import com.yadony.api.kyc.dto.KycAdminStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Lot C — KYC d'un utilisateur vu par l'administration.
 *
 * <p>Contrôleur séparé d'{@code AdminUserController} : celui-ci porte déjà dix gestes de
 * compte, et la vue KYC dépend d'un service et d'un DTO qui lui sont propres.
 *
 * <p>SUPPORT possède {@code USER_KYC} ({@code AdminRole.SUPPORT}) : il a accès aux deux
 * endpoints. C'est délibéré — un reset KYC ne détruit aucune donnée, il remet l'utilisateur
 * en état de refaire sa vérification. Le geste irréversible du lot est l'exécution RGPD,
 * fermée à SUPPORT.
 *
 * <p>Une annotation {@code @PreAuthorize} de méthode remplace celle de classe, elle ne s'y
 * ajoute pas : chaque méthode re-déclare donc explicitement {@code hasRole('ADMIN')} en plus
 * de son authority, comme {@code AdminUserController#muteMessaging}.
 */
@RestController
@RequestMapping("/admin/users/{userId}/kyc")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserKycController {

    private final KycAdminService kycAdminService;

    public AdminUserKycController(KycAdminService kycAdminService) {
        this.kycAdminService = kycAdminService;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_KYC')")
    @GetMapping
    public KycAdminStatusResponse get(@PathVariable UUID userId) {
        return kycAdminService.getForUser(userId);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_KYC')")
    @PostMapping("/reset")
    public KycAdminStatusResponse reset(@PathVariable UUID userId,
                                        @RequestBody @Valid KycResetRequest request,
                                        Authentication authentication) {
        return kycAdminService.resetForUser(userId, adminId(authentication), request.reason());
    }

    /** Même extraction que {@code AdminAnnouncementModerationController} : l'audit doit
     *  porter l'identifiant de l'administrateur, jamais celui de l'utilisateur ciblé. */
    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
