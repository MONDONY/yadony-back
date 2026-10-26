package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminRatingContextResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Contexte d'un avis ouvert dans le poste de modération (consultation seule). */
@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('RATING_MODERATE')")
public class AdminRatingContextController {

    private final RatingRepository ratingRepo;
    private final RatingContextService contextService;

    public AdminRatingContextController(RatingRepository ratingRepo, RatingContextService contextService) {
        this.ratingRepo = ratingRepo;
        this.contextService = contextService;
    }

    /** Un avis supprimé reste consultable : la corbeille sert à décider d'une restauration. */
    @GetMapping("/admin/ratings/{id}/context")
    public ResponseEntity<AdminRatingContextResponse> context(@PathVariable UUID id) {
        RatingEntity rating = ratingRepo.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "rating-not-found", "Not Found", "Avis introuvable"));
        return ResponseEntity.ok(contextService.build(rating));
    }
}
