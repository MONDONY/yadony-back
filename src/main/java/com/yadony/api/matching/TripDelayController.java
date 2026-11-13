package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.TripDelayInfo;
import com.yadony.api.matching.dto.TripDelayRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Annonce de retard d'un trajet en cours par son voyageur (nouvelle fourchette d'arrivée estimée). */
@RestController
@RequestMapping("/announcements")
public class TripDelayController {

    private final TripDelayService delayService;

    public TripDelayController(TripDelayService delayService) {
        this.delayService = delayService;
    }

    @PostMapping("/{id}/delays")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<TripDelayInfo> announceDelay(
            @PathVariable UUID id,
            @Valid @RequestBody TripDelayRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(delayService.announce(id, requireFirebaseUid(), request));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized", "problem.auth.token-required");
        }
        return (String) auth.getPrincipal();
    }
}
