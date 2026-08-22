package com.yadony.api.favorites;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.favorites.dto.FavoriteIdsResponse;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/favorites")
public class FavoriteController {

    private final FavoriteService service;
    private final UserRepository userRepository;

    public FavoriteController(FavoriteService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /**
     * Add a favorite (toggle-on).
     * trip      → SENDER or TRAVELER
     * package-request → TRAVELER only (SENDER → 403 via AccessDeniedException)
     * unknown type  → 400 via YadonyBusinessException thrown by FavoriteTargetType.fromPath
     */
    @PutMapping("/{type}/{targetId}")
    // 'GUEST' : un visiteur peut mettre un trajet de côté avant de s'inscrire. La règle
    // centrale de SecurityConfig continue de lui fermer tout le reste ; ici l'annotation
    // ne fait que cesser de le refuser après la chaîne de filtres. Aucun rôle existant
    // ne change de périmètre.
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER','GUEST')")
    public ResponseEntity<Void> add(@AuthenticationPrincipal String firebaseUid,
                                    @PathVariable String type,
                                    @PathVariable UUID targetId) {
        FavoriteTargetType targetType = FavoriteTargetType.fromPath(type); // 400 on unknown
        enforcePackageRequestRequiresTraveler(targetType);
        service.addFavorite(firebaseUid, targetType, targetId);
        return ResponseEntity.ok().build();
    }

    /**
     * Remove a favorite (toggle-off). 204 No Content.
     */
    @DeleteMapping("/{type}/{targetId}")
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER','GUEST')")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal String firebaseUid,
                                       @PathVariable String type,
                                       @PathVariable UUID targetId) {
        service.removeFavorite(viewerUserIdOrNull(firebaseUid), FavoriteTargetType.fromPath(type), targetId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Favorite trips — both roles.
     */
    @GetMapping("/trips")
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER','GUEST')")
    public List<AnnouncementSearchResponse> getFavoriteTrips(
            @AuthenticationPrincipal String firebaseUid) {
        return service.getFavoriteTrips(viewerUserIdOrNull(firebaseUid));
    }

    /**
     * Favorite package-requests — TRAVELER, plus GUEST (declared at @PreAuthorize level).
     *
     * <p>Un invité peut lire cette liste et l'alimenter : le garde privé
     * {@code enforcePackageRequestRequiresTraveler} l'accepte au même titre qu'un
     * voyageur. Un expéditeur reste exclu, comme avant.
     */
    @GetMapping("/package-requests")
    @PreAuthorize("hasAnyRole('TRAVELER','GUEST')")
    public List<PackageRequestSearchResponse> getFavoritePackageRequests(
            @AuthenticationPrincipal String firebaseUid) {
        return service.getFavoritePackageRequests(viewerUserIdOrNull(firebaseUid));
    }

    /**
     * IDs of all favorites for the current user — any authenticated user.
     */
    @GetMapping("/ids")
    @PreAuthorize("isAuthenticated()")
    public FavoriteIdsResponse getFavoriteIds(@AuthenticationPrincipal String firebaseUid) {
        return service.getFavoriteIds(viewerUserIdOrNull(firebaseUid));
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    /**
     * Identifiant de l'appelant pour les lectures et la suppression, ou {@code null}
     * pour un invité.
     *
     * <p>Un invité n'a pas de ligne {@code users} tant qu'il n'a rien favorité : la
     * matérialisation est paresseuse, et tout son intérêt est que naviguer (ou retirer
     * un favori qui ne peut pas exister) ne laisse aucune trace. On ne provisionne donc
     * surtout pas ici ; {@link FavoriteService} traite ce {@code null} comme « aucun
     * favori, rien à retirer ».
     *
     * <p>La tolérance est délibérément réservée aux invités : pour tout autre appelant,
     * l'absence de ligne reste un 404 {@code user/not-found}, exactement comme avant
     * l'ouverture aux invités. Même idiome que
     * {@code PackageRequestController.viewerUserIdOrNull}.
     */
    private UUID viewerUserIdOrNull(String firebaseUid) {
        if (isGuest()) {
            return null;
        }
        return requireUserId(firebaseUid);
    }

    private UUID requireUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }

    /**
     * Vrai si l'appelant est une session Firebase anonyme.
     *
     * <p>On teste l'autorité posée par {@code FirebaseTokenFilter} plutôt que de relire le
     * claim du token : c'est le filtre qui fait autorité sur « cette session est-elle
     * anonyme ? », le relire ici en ferait une seconde source de vérité.
     */
    private boolean isGuest() {
        return com.yadony.api.common.GuestSession.isGuest();
    }

    // ── private guard ─────────────────────────────────────────────────────────

    /**
     * A SENDER must not be able to favourite a package-request (only travelers browse them).
     * Throws {@link AccessDeniedException} which the project maps to HTTP 403.
     *
     * <p>ROLE_GUEST est accepté au même titre que ROLE_TRAVELER : un visiteur peut
     * parcourir les colis et lire {@code GET /favorites/package-requests}, le laisser
     * dehors ici lui donnerait une liste qu'il ne pourrait jamais alimenter. Rien ne
     * change pour SENDER ni pour TRAVELER.
     */
    private void enforcePackageRequestRequiresTraveler(FavoriteTargetType type) {
        if (type != FavoriteTargetType.PACKAGE_REQUEST) return;

        boolean canBrowsePackageRequests = SecurityContextHolder.getContext()
                .getAuthentication()
                .getAuthorities()
                .stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_TRAVELER")
                        || a.getAuthority().equals("ROLE_GUEST"));

        if (!canBrowsePackageRequests) {
            throw new AccessDeniedException(
                    "Seul un voyageur peut mettre une demande d'envoi en favori");
        }
    }
}
