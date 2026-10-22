package com.yadony.api.listings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.listings.dto.UserListingItem;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserListingsController {

    private final UserListingsService userListingsService;
    private final UserRepository userRepository;

    public UserListingsController(UserListingsService userListingsService, UserRepository userRepository) {
        this.userListingsService = userListingsService;
        this.userRepository = userRepository;
    }

    /** Trajets et demandes d'envoi ouverts de {@code userId}, du plus récent au plus ancien. */
    @GetMapping("/{userId}/open-listings")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<List<UserListingItem>> openListings(@PathVariable UUID userId) {
        return ResponseEntity.ok(userListingsService.openListings(userId, viewerUserIdOrNull()));
    }

    /** Même résolution tolérante que {@code ProfilePublicController} : un viewer inconnu n'a bloqué personne. */
    private UUID viewerUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String uid)
                || "anonymousUser".equals(uid)) {
            return null;
        }
        return userRepository.findByFirebaseUid(uid).map(UserEntity::getId).orElse(null);
    }
}
