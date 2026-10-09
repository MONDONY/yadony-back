package com.yadony.api.payments.overview;

import com.yadony.api.payments.overview.dto.MoneyOverviewResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /payments/me/overview} — aperçu « Mon argent » de l'utilisateur courant (FLUTTER-HV).
 * Lecture seule ; ne prend aucun identifiant en paramètre : la propriété est garantie par
 * construction (seuls les colis de l'appelant sont lus).
 */
@RestController
public class MoneyOverviewController {

    private final MoneyOverviewService service;

    public MoneyOverviewController(MoneyOverviewService service) {
        this.service = service;
    }

    @GetMapping("/payments/me/overview")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<MoneyOverviewResponse> overview() {
        return ResponseEntity.ok(service.overview(requireFirebaseUid()));
    }

    /** Jamais anonyme ici : {@code @PreAuthorize} a déjà exigé un rôle (401 sinon). */
    private static String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (String) auth.getPrincipal();
    }
}
