package com.yadony.api.alerts;

import com.yadony.api.alerts.dto.TripSpaceAlertResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** « Me prévenir si de la place se libère » sur un trajet complet (FLUTTER-JH). */
@RestController
public class TripSpaceAlertController {

    private final TripSpaceAlertService service;

    public TripSpaceAlertController(TripSpaceAlertService service) {
        this.service = service;
    }

    @GetMapping("/me/trip-space-alerts")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    public List<TripSpaceAlertResponse> list(@AuthenticationPrincipal String firebaseUid) {
        return service.list(firebaseUid);
    }

    /** Idempotent : renvoie l'abonnement en attente s'il existe déjà. */
    @PutMapping("/me/trip-space-alerts/{announcementId}")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    public TripSpaceAlertResponse subscribe(@AuthenticationPrincipal String firebaseUid,
                                            @PathVariable UUID announcementId) {
        return service.subscribe(firebaseUid, announcementId);
    }

    @DeleteMapping("/me/trip-space-alerts/{announcementId}")
    @PreAuthorize("hasAnyRole('TRAVELER','SENDER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal String firebaseUid,
                            @PathVariable UUID announcementId) {
        service.unsubscribe(firebaseUid, announcementId);
    }
}
