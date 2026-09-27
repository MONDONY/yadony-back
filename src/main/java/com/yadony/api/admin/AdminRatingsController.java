package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminRatingResponse;
import com.yadony.api.admin.dto.ExcludeRatingRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('RATING_MODERATE')")
public class AdminRatingsController {

    private final RatingRepository ratingRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    public AdminRatingsController(RatingRepository ratingRepo,
                                  UserRepository userRepo,
                                  AuditService auditService) {
        this.ratingRepo = ratingRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
    }

    @GetMapping("/admin/ratings")
    public ResponseEntity<Page<AdminRatingResponse>> listRatings(
            @RequestParam(required = false) Boolean flagged,
            @RequestParam(required = false) Boolean flaggedOnly,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Integer maxScore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Le front envoie flaggedOnly=true ; flagged reste accepté (compat).
        Boolean flaggedFilter = flagged != null ? flagged
                : (Boolean.TRUE.equals(flaggedOnly) ? Boolean.TRUE : null);
        Page<RatingEntity> entities = ratingRepo.findAdminFiltered(
                flaggedFilter, minScore, maxScore,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        // Batch load all referenced users
        Set<UUID> userIds = new HashSet<>();
        for (RatingEntity r : entities.getContent()) {
            if (r.getRaterId() != null) userIds.add(r.getRaterId());
            if (r.getRatedUserId() != null) userIds.add(r.getRatedUserId());
        }
        List<UserEntity> users = userRepo.findAllById(userIds);
        Map<UUID, UserEntity> usersById = users.stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        Page<AdminRatingResponse> result = entities.map(r -> AdminRatingResponse.from(r, usersById));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/admin/ratings/{id}/exclude")
    @Transactional
    public ResponseEntity<AdminRatingResponse> excludeRating(@PathVariable UUID id,
                                                              @RequestBody ExcludeRatingRequest request,
                                                              Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        RatingEntity rating = findRatingOrThrow(id);
        rating.setExcludedFromAverage(request.excluded());
        rating.setExcludedReason(request.excluded() ? request.reason() : null);
        ratingRepo.save(rating);
        auditService.log("RATING", id, "RATING_EXCLUDED", adminId,
                Map.of("ratingId", id.toString(), "reason", request.reason() != null ? request.reason() : ""));

        Map<UUID, UserEntity> usersById = buildUsersMap(rating);
        return ResponseEntity.ok(AdminRatingResponse.from(rating, usersById));
    }

    /** Longueur maximale du motif de suppression, conservé à vie dans {@code audit_log}. */
    static final int MAX_DELETE_REASON_LENGTH = 500;

    // Lot C : suppression definitive detachee de la moderation courante. L'annotation de
    // methode REMPLACE celle de classe (elle ne s'y ajoute pas), donc les deux conditions
    // sont re-declarees ici.
    //
    // Le motif (facultatif) part dans le detail de l'audit. Trop long, il est REFUSE (400)
    // plutot que tronque : audit_log est immuable, une trace amputee ne se corrige jamais.
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('RATING_DELETE')")
    @DeleteMapping("/admin/ratings/{id}")
    @Transactional
    public ResponseEntity<Void> deleteRating(@PathVariable UUID id,
                                             @RequestParam(required = false) String reason,
                                             Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        String normalizedReason = reason != null ? reason.trim() : "";
        if (normalizedReason.length() > MAX_DELETE_REASON_LENGTH) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                    "rating-delete-reason-too-long", "Rating Delete Reason Too Long",
                    "Le motif de suppression ne doit pas dépasser "
                            + MAX_DELETE_REASON_LENGTH + " caractères");
        }
        RatingEntity rating = findRatingOrThrow(id);
        rating.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        ratingRepo.save(rating);
        auditService.log("RATING", id, "RATING_DELETED", adminId,
                Map.of("ratingId", id.toString(), "reason", normalizedReason));
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RatingEntity findRatingOrThrow(UUID id) {
        return ratingRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "rating-not-found", "Not Found", "Avis introuvable"));
    }

    private Map<UUID, UserEntity> buildUsersMap(RatingEntity r) {
        Set<UUID> userIds = new HashSet<>();
        if (r.getRaterId() != null) userIds.add(r.getRaterId());
        if (r.getRatedUserId() != null) userIds.add(r.getRatedUserId());
        List<UserEntity> users = userRepo.findAllById(userIds);
        return users.stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
    }
}
