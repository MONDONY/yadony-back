package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.KycApproveRequest;
import com.yadony.api.admin.dto.KycRejectRequest;
import com.yadony.api.admin.dto.KycResetRequest;
import com.yadony.api.admin.dto.KycRevokeRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.KycAdminReviewService;
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
 * <p>Les décisions (valider, refuser, révoquer) exigent {@code KYC_DECIDE}, que SUPPORT n'a
 * pas : un compte vérifié ouvre la publication et les paiements. Chaque décision rend la fiche
 * enrichie, relue APRÈS la transaction (la relecture chez le fournisseur est un appel réseau).
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
    private final KycAdminReviewService review;

    public AdminUserKycController(KycAdminService kycAdminService, KycAdminReviewService review) {
        this.kycAdminService = kycAdminService;
        this.review = review;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_KYC')")
    @GetMapping
    public KycAdminStatusResponse get(@PathVariable UUID userId) {
        return review.detail(userId);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('KYC_DECIDE')")
    @PostMapping("/approve")
    public KycAdminStatusResponse approve(@PathVariable UUID userId,
                                          @RequestBody @Valid KycApproveRequest request,
                                          Authentication authentication) {
        review.approve(userId, AdminPrincipal.requireAdminId(authentication), request.reason());
        return review.detail(userId);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('KYC_DECIDE')")
    @PostMapping("/reject")
    public KycAdminStatusResponse reject(@PathVariable UUID userId,
                                         @RequestBody @Valid KycRejectRequest request,
                                         Authentication authentication) {
        review.reject(userId, AdminPrincipal.requireAdminId(authentication), request.code(), request.reason());
        return review.detail(userId);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('KYC_DECIDE')")
    @PostMapping("/revoke")
    public KycAdminStatusResponse revoke(@PathVariable UUID userId,
                                         @RequestBody @Valid KycRevokeRequest request,
                                         Authentication authentication) {
        review.revoke(userId, AdminPrincipal.requireAdminId(authentication), request.code(), request.reason());
        return review.detail(userId);
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
