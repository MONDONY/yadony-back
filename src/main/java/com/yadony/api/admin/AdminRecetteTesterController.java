package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.RecetteMode;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Désignation des comptes testeurs du mode recette (FLUTTER-FA, FLUTTER-FB).
 *
 * <p>Réservé au super-administrateur ({@code ADMIN_MANAGE}) : un testeur contourne deux
 * garde-fous métier. Refusé (409 {@code recette-disabled}) dans un environnement où le mode est
 * fermé, la production notamment : aucun compte de prod ne peut porter le drapeau par ce chemin.
 * Chaque changement effectif est tracé ({@code RECETTE_TESTER_GRANTED / _REVOKED}).
 */
@RestController
@RequestMapping("/admin/users/{userId}/recette-tester")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('ADMIN_MANAGE')")
public class AdminRecetteTesterController {

    static final String ACTION_GRANTED = "RECETTE_TESTER_GRANTED";
    static final String ACTION_REVOKED = "RECETTE_TESTER_REVOKED";

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RecetteMode recetteMode;

    public AdminRecetteTesterController(UserRepository userRepository, AuditService auditService,
                                        RecetteMode recetteMode) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.recetteMode = recetteMode;
    }

    public record RecetteTesterRequest(@NotNull Boolean enabled) {}

    /**
     * @param recetteTester   drapeau stocké sur le compte
     * @param recetteModeActive le mode s'applique réellement à ce compte dans cet environnement
     */
    public record RecetteTesterResponse(UUID userId, boolean recetteTester, boolean recetteModeActive) {}

    @GetMapping
    public RecetteTesterResponse get(@PathVariable UUID userId) {
        return toResponse(requireUser(userId));
    }

    @PutMapping
    @Transactional
    public RecetteTesterResponse set(@PathVariable UUID userId,
                                     @Valid @RequestBody RecetteTesterRequest request,
                                     Authentication authentication) {
        UUID adminId = adminId(authentication);
        if (!recetteMode.isEnabled()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "recette-disabled",
                    "Recette Disabled",
                    "Le mode recette n'est pas activé dans cet environnement");
        }
        UserEntity user = requireUser(userId);
        boolean enabled = request.enabled();
        if (user.isRecetteTester() != enabled) {
            user.setRecetteTester(enabled);
            userRepository.save(user);
            auditService.log("USER", userId, enabled ? ACTION_GRANTED : ACTION_REVOKED, adminId,
                    Map.of("recetteTester", enabled));
        }
        return toResponse(user);
    }

    private RecetteTesterResponse toResponse(UserEntity user) {
        return new RecetteTesterResponse(user.getId(), user.isRecetteTester(), recetteMode.appliesTo(user));
    }

    private UserEntity requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "Not Found", "Utilisateur introuvable"));
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
