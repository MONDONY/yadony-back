package com.yadony.api.ratings;

import com.yadony.api.ratings.dto.RatingResponse;
import com.yadony.api.ratings.dto.ReceptionRatingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Le destinataire confirmé note le voyageur après la livraison (FLUTTER-CA). Tout rôle. */
@RestController
@PreAuthorize("isAuthenticated()")
public class ReceptionRatingController {

    private final ReceptionRatingService service;

    public ReceptionRatingController(ReceptionRatingService service) {
        this.service = service;
    }

    @PostMapping("/receptions/{bidId}/rating")
    public ResponseEntity<RatingResponse> rate(@AuthenticationPrincipal String firebaseUid,
                                               @PathVariable UUID bidId,
                                               @Valid @RequestBody ReceptionRatingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.rate(bidId, firebaseUid, request));
    }
}
