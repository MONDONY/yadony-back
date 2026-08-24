package com.yadony.api.favorites;

import com.yadony.api.auth.GuestUserProvisioner;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.favorites.dto.FavoriteIdsResponse;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementSearchMapper;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.service.PackageRequestSearchMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final AnnouncementSearchMapper announcementSearchMapper;
    private final PackageRequestSearchMapper packageRequestSearchMapper;
    private final GuestUserProvisioner guestUserProvisioner;

    public FavoriteService(FavoriteRepository favoriteRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository,
                           PackageRequestRepository packageRequestRepository,
                           AnnouncementSearchMapper announcementSearchMapper,
                           PackageRequestSearchMapper packageRequestSearchMapper,
                           GuestUserProvisioner guestUserProvisioner) {
        this.favoriteRepository = favoriteRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.announcementSearchMapper = announcementSearchMapper;
        this.packageRequestSearchMapper = packageRequestSearchMapper;
        this.guestUserProvisioner = guestUserProvisioner;
    }

    /**
     * Toggle-add: inserts a new favorite if absent. Idempotent if the row already
     * exists — including under a concurrent double-insert, where the unique index
     * {@code ux_favorites_active} rejects the second insert and the resulting
     * {@link DataIntegrityViolationException} is swallowed.
     *
     * @throws YadonyNotFoundException   if the target does not exist
     * @throws YadonyBusinessException   (422) if the caller owns the TRIP target
     */
    public void addFavorite(String firebaseUid, FavoriteTargetType type, UUID targetId) {
        UUID userId = resolveUserId(firebaseUid);
        validateTargetExistsAndNotOwned(userId, type, targetId);

        if (favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId)) {
            return;
        }
        try {
            favoriteRepository.save(new FavoriteEntity(userId, type, targetId));
        } catch (DataIntegrityViolationException e) {
            // course entre deux ajouts simultanés : la ligne existe déjà -> no-op
        }
    }

    /**
     * Physically deletes the favorite row if present. No-op otherwise (whether because
     * the favorite doesn't exist, or because {@code callerId} is {@code null}).
     *
     * <p>{@code callerId} est {@code null} exactement quand l'appelant est un invité sans
     * ligne {@code users} (résolu par {@code FavoriteController.viewerUserIdOrNull}) : il
     * n'a par construction aucun favori, on ne provisionne donc pas ici, ce serait créer
     * une ligne fantôme pour retirer quelque chose qui ne peut pas exister. Pour tout
     * autre appelant, le contrôleur a déjà transformé une ligne absente en 404 avant
     * d'arriver ici — le service ne voit donc jamais ce cas-là comme un {@code null}.
     */
    public void removeFavorite(UUID callerId, FavoriteTargetType type, UUID targetId) {
        if (callerId == null) return;
        favoriteRepository.findByUserIdAndTargetTypeAndTargetId(callerId, type, targetId)
                .ifPresent(favoriteRepository::delete);
    }

    /**
     * Returns the sets of favorite target IDs for the caller, split by type.
     *
     * <p>Chemin de lecture : {@code callerId == null} signifie un invité sans ligne
     * {@code users} (jamais posé de favori), qui reçoit donc des ensembles vides plutôt
     * qu'une erreur ou un provisionnement. Voir {@link #removeFavorite} pour le contrat
     * complet de {@code callerId}.
     */
    @Transactional(readOnly = true)
    public FavoriteIdsResponse getFavoriteIds(UUID callerId) {
        if (callerId == null) {
            return new FavoriteIdsResponse(Set.of(), Set.of());
        }
        Set<UUID> trips = new HashSet<>(favoriteRepository.findTargetIds(callerId, FavoriteTargetType.TRIP));
        Set<UUID> packageRequests = new HashSet<>(
                favoriteRepository.findTargetIds(callerId, FavoriteTargetType.PACKAGE_REQUEST));
        return new FavoriteIdsResponse(trips, packageRequests);
    }

    /**
     * Returns the caller's favorite trips as enriched DTOs, with {@code isFavorite=true}.
     * Soft-deleted announcements are automatically excluded (via {@code @Where} on the entity).
     * Announcements with status {@code CANCELLED}, {@code COMPLETED}, {@code DRAFT} or
     * {@code REMOVED_BY_ADMIN} are also filtered out (masquage immédiat à la lecture ; le
     * nettoyage effectif en base est fait par {@link FavoriteCleanupScheduler}).
     * Batch-loads users, bid counts, and grid items in 3 queries total (no N+1).
     */
    @Transactional(readOnly = true)
    public List<AnnouncementSearchResponse> getFavoriteTrips(UUID callerId) {
        if (callerId == null) return List.of();
        List<UUID> ids = favoriteRepository.findTargetIds(callerId, FavoriteTargetType.TRIP);
        if (ids.isEmpty()) return List.of();
        List<AnnouncementEntity> active = announcementRepository.findAllById(ids).stream()
                .filter(a -> a.getStatus() != AnnouncementStatus.CANCELLED
                        && a.getStatus() != AnnouncementStatus.COMPLETED
                        && a.getStatus() != AnnouncementStatus.DRAFT
                        // Lot B (correction 3) : un trajet retiré par la modération ne doit pas
                        // rester visible dans les favoris de l'utilisateur.
                        && a.getStatus() != AnnouncementStatus.REMOVED_BY_ADMIN)
                .toList();
        if (active.isEmpty()) return List.of();
        Set<UUID> favIdSet = new HashSet<>(ids); // all are favorites
        return announcementSearchMapper.toSearchResponseList(active, favIdSet);
    }

    /**
     * Returns the caller's favorite package-requests as enriched DTOs, with {@code isFavorite=true}.
     * Soft-deleted, cancelled, completed or expired package-requests are excluded (masquage
     * immédiat à la lecture ; le nettoyage effectif en base est fait par
     * {@link FavoriteCleanupScheduler}).
     * Batch-loads users, cities, and photos in 3 queries total (no N+1).
     */
    @Transactional(readOnly = true)
    public List<PackageRequestSearchResponse> getFavoritePackageRequests(UUID callerId) {
        if (callerId == null) return List.of();
        List<UUID> ids = favoriteRepository.findTargetIds(callerId, FavoriteTargetType.PACKAGE_REQUEST);
        if (ids.isEmpty()) return List.of();
        List<com.yadony.api.requests.entity.PackageRequestEntity> active =
                packageRequestRepository.findAllById(ids).stream()
                        .filter(pr -> pr.getStatus() != PackageRequestStatus.CANCELLED
                                && pr.getStatus() != PackageRequestStatus.COMPLETED
                                && pr.getStatus() != PackageRequestStatus.EXPIRED
                                && pr.getStatus() != PackageRequestStatus.DRAFT)
                        .toList();
        if (active.isEmpty()) return List.of();
        Set<UUID> favIdSet = new HashSet<>(ids); // all are favorites
        boolean viewerHasConnect = userRepository.findById(callerId)
                .map(UserEntity::hasActiveStripeConnect)
                .orElse(false);
        return packageRequestSearchMapper.toSearchResponseList(active, favIdSet, viewerHasConnect);
    }

    // --- private helpers ---

    /**
     * Résout l'id utilisateur pour un chemin qui persiste réellement quelque chose pour
     * l'appelant. Un visiteur anonyme n'a pas encore de ligne : la poser ici (et pas au
     * lancement de l'app, ni sur un chemin de lecture) est ce qui rend la matérialisation
     * paresseuse. Réservé à {@link #addFavorite}.
     */
    private UUID resolveUserId(String firebaseUid) {
        return guestUserProvisioner.resolveOrProvision(firebaseUid);
    }

    /**
     * Validates that the target exists (404 if missing) and that the caller does not
     * own it (422 if the caller is the traveler of a TRIP target).
     * PACKAGE_REQUEST has no ownership check — a traveler is never the sender/owner.
     */
    private void validateTargetExistsAndNotOwned(UUID userId, FavoriteTargetType type, UUID targetId) {
        switch (type) {
            case TRIP -> {
                AnnouncementEntity trip = announcementRepository.findById(targetId)
                        .orElseThrow(() -> new YadonyNotFoundException("Trajet introuvable: " + targetId));
                if (trip.getStatus() == AnnouncementStatus.DRAFT) {
                    throw new YadonyNotFoundException("Trajet introuvable: " + targetId);
                }
                if (userId.equals(trip.getTravelerId())) {
                    throw new YadonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "favorites/own-resource",
                            "Cannot Favorite Own Resource",
                            "Impossible de mettre son propre trajet en favori"
                    );
                }
            }
            case PACKAGE_REQUEST -> {
                var request = packageRequestRepository.findById(targetId)
                        .orElseThrow(() -> new YadonyNotFoundException(
                                "Demande d'envoi introuvable: " + targetId));
                if (request.getStatus() == PackageRequestStatus.DRAFT) {
                    throw new YadonyNotFoundException("Demande d'envoi introuvable: " + targetId);
                }
            }
        }
    }
}
