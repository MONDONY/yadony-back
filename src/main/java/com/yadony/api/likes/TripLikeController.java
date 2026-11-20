package com.yadony.api.likes;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.likes.dto.TripLikeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Aimer / ne plus aimer un trajet. Réservé aux vrais comptes : un invité n'y accède pas
 * (la règle finale de SecurityConfig le refuse, et {@code @PreAuthorize} l'exclut aussi).
 */
@RestController
@RequestMapping("/announcements/{id}/like")
public class TripLikeController {

    private final TripLikeService service;
    private final UserRepository userRepository;

    public TripLikeController(TripLikeService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER')")
    public TripLikeResponse like(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id) {
        return service.like(requireUserId(firebaseUid), id);
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('SENDER','TRAVELER')")
    public TripLikeResponse unlike(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id) {
        return service.unlike(requireUserId(firebaseUid), id);
    }

    private UUID requireUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }
}
