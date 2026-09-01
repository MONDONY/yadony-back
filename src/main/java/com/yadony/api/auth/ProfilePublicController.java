package com.yadony.api.auth;

import com.yadony.api.auth.dto.ProfilePublicResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/users")
public class ProfilePublicController {

    private final ProfilePublicService profilePublicService;
    private final UserRepository userRepository;

    public ProfilePublicController(ProfilePublicService profilePublicService,
                                   UserRepository userRepository) {
        this.profilePublicService = profilePublicService;
        this.userRepository = userRepository;
    }

    @GetMapping("/{userId}/profile-public")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<ProfilePublicResponse> getProfilePublic(@PathVariable UUID userId) {
        return ResponseEntity.ok(profilePublicService.getProfilePublic(userId, viewerUserIdOrNull()));
    }

    /**
     * Identifiant de l'appelant, ou {@code null} s'il n'est pas identifiable.
     *
     * <p>Le service s'en sert pour masquer le profil d'un compte bloqué. On reste
     * tolérant plutôt que de lever un 404 sur une ligne {@code users} absente : la
     * résolution du viewer n'est pas ce que l'appelant est venu chercher, et un viewer
     * inconnu ne peut de toute façon avoir bloqué personne.
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
