package com.yadony.api.ratings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.ratings.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/ratings")
public class RatingController {

    private final RatingService ratingService;
    private final UserRepository userRepository;

    public RatingController(RatingService ratingService, UserRepository userRepository) {
        this.ratingService = ratingService;
        this.userRepository = userRepository;
    }

    // Story 9.1 — Expéditeur authentifié note le voyageur
    @PostMapping
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<RatingResponse> createRating(Principal principal,
                                                       @Valid @RequestBody RatingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ratingService.createRating(principal.getName(), request));
    }

    // Story 9.2 — Destinataire sans compte note le voyageur (endpoint public)
    @PostMapping("/recipient")
    public ResponseEntity<RatingResponse> createRecipientRating(@Valid @RequestBody RecipientRatingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ratingService.createRecipientRating(request));
    }

    // Story 9.3 — Voyageur note l'expéditeur
    @PostMapping("/traveler-to-sender")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<RatingResponse> createTravelerRating(Principal principal,
                                                               @Valid @RequestBody TravelerRatingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ratingService.createTravelerRating(principal.getName(), request));
    }

    // Profil public — liste paginée des notes reçues par un utilisateur.
    // Endpoint ouvert : l'appelant peut être anonyme, d'où un viewer nullable.
    @GetMapping("/user/{userId}")
    public ResponseEntity<UserRatingsSummaryResponse> getUserRatings(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ratingService.getUserRatings(userId, page, size, viewerUserIdOrNull()));
    }

    // Notation en attente au démarrage de l'app
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<PendingRatingResponse> getPendingRating(Principal principal) {
        return ratingService.getPendingRating(principal.getName())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    // Notes reçues par l'utilisateur connecté (mes notes reçues)
    @GetMapping("/me/received")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<UserRatingsSummaryResponse> getMyReceivedRatings(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ratingService.getMyReceivedRatings(auth.getName(), page, size));
    }

    /**
     * Identifiant de l'appelant, ou {@code null} s'il n'est pas identifiable (visiteur
     * anonyme, ou jeton sans ligne {@code users}).
     *
     * <p>Sert au masquage des comptes bloqués. On reste tolérant sur l'absence de ligne
     * plutôt que de lever un 404 : un viewer inconnu n'a bloqué personne, et l'endpoint
     * doit continuer de répondre aux appels anonymes.
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
