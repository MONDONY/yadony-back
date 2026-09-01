package com.yadony.api.auth;

import com.yadony.api.auth.dto.PublicTravelerProfileResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public (no-auth) shareable traveler profile.
 * Registered under {@code /public/**} which is permitAll in SecurityConfig.
 */
@RestController
@RequestMapping("/public/travelers")
public class PublicTravelerController {

    private final ProfilePublicService profilePublicService;
    private final UserRepository userRepository;

    public PublicTravelerController(ProfilePublicService profilePublicService,
                                    UserRepository userRepository) {
        this.profilePublicService = profilePublicService;
        this.userRepository = userRepository;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<PublicTravelerProfileResponse> getPublicProfile(@PathVariable UUID userId) {
        return ResponseEntity.ok(profilePublicService.getPublicTravelerProfile(userId, viewerUserIdOrNull()));
    }

    /**
     * Identifiant de l'appelant s'il se présente avec un jeton valide, {@code null} sinon.
     *
     * <p>L'endpoint est ouvert : la plupart des appels sont anonymes et ne subissent
     * aucun masquage. Quand un utilisateur connecté ouvre le lien, en revanche, la règle
     * de blocage s'applique comme sur le profil interne.
     */
    private UUID viewerUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String uid)
                || "anonymousUser".equals(uid)) {
            return null;
        }
        return userRepository.findByFirebaseUid(uid).map(UserEntity::getId).orElse(null);
    }
}
