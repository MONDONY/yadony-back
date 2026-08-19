package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.PlatformSettingResponse;
import com.yadony.api.admin.dto.PlatformSettingUpdateRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingView;
import com.yadony.api.config.PlatformSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Lot D — parametres plateforme. Reserve a ADMIN/SUPER_ADMIN : SUPPORT ne recoit pas
 * {@code CONFIG_MANAGE}.
 *
 * <p>⚠️ Chaque methode re-declare l'expression COMPLETE : une {@code @PreAuthorize} de
 * methode <b>remplace</b> celle de la classe, elle ne s'y ajoute pas. Et {@code hasRole('ADMIN')}
 * seule ne filtrerait rien — tout compte admin la porte, SUPPORT compris.
 *
 * <p>La lecture est protegee au meme titre que l'ecriture : le taux de commission global et
 * l'etat des SMS renseignent sur l'economie de la plateforme.
 */
@RestController
@RequestMapping("/admin/settings")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
public class AdminSettingsController {

    private final PlatformSettingsService settingsService;
    private final AdminUserRepository adminUserRepository;

    public AdminSettingsController(PlatformSettingsService settingsService,
                                   AdminUserRepository adminUserRepository) {
        this.settingsService = settingsService;
        this.adminUserRepository = adminUserRepository;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @GetMapping
    public List<PlatformSettingResponse> list() {
        List<PlatformSettingView> views = settingsService.listByKey();
        Map<UUID, String> emails = emailsOf(views);
        return views.stream()
                // Le garde sur null n'est pas decoratif : emails peut etre un Map.of(), qui
                // leve NullPointerException sur un get(null) — pas seulement le refuse.
                .map(view -> PlatformSettingResponse.from(view,
                        view.updatedBy() == null ? null : emails.get(view.updatedBy())))
                .toList();
    }

    /**
     * L'audit_log est ecrit par {@code PlatformSettingsService.updateOne}, avec l'ancienne et
     * la nouvelle valeur — pas ici : une seule ecriture, une seule trace.
     *
     * <p>Une cle inconnue est rejetee en 422 par {@link PlatformSettingKey#fromKey(String)} :
     * on n'invente pas un reglage a partir d'un chemin.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PutMapping("/{key}")
    public PlatformSettingResponse update(@PathVariable String key,
                                          @RequestBody @Valid PlatformSettingUpdateRequest request,
                                          Authentication authentication) {
        PlatformSettingView view = settingsService.updateOne(
                PlatformSettingKey.fromKey(key), request.value(), adminId(authentication));
        return PlatformSettingResponse.from(view, emailOf(view.updatedBy()));
    }

    /** Une seule requete pour tous les auteurs : l'ecran affiche un email, pas un UUID. */
    private Map<UUID, String> emailsOf(List<PlatformSettingView> views) {
        Set<UUID> ids = views.stream()
                .map(PlatformSettingView::updatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return StreamSupport.stream(adminUserRepository.findAllById(ids).spliterator(), false)
                .collect(Collectors.toMap(AdminUserEntity::getId, AdminUserEntity::getEmail));
    }

    private String emailOf(UUID adminId) {
        return adminId == null ? null
                : adminUserRepository.findById(adminId)
                        .map(AdminUserEntity::getEmail)
                        .orElse(null);
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
