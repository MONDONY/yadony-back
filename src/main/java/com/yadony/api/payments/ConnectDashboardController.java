package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.dto.DashboardLinkResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /payments/connect/dashboard-link} : lien à usage unique vers le tableau de bord
 * Stripe Express du voyageur courant (changer de compte bancaire). 404 {@code stripe-account-missing}
 * sans compte Connect, 409 {@code stripe-onboarding-incomplete} si l'inscription Stripe n'est pas
 * terminée (l'app propose alors {@code POST /payments/connect/onboarding-link}). Limité comme les
 * autres routes {@code /payments/connect/**} (RateLimitRule PAYMENT).
 */
@RestController
public class ConnectDashboardController {

    private final ConnectDashboardLinkService service;

    public ConnectDashboardController(ConnectDashboardLinkService service) {
        this.service = service;
    }

    @PostMapping("/payments/connect/dashboard-link")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<DashboardLinkResponse> createDashboardLink() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.createDashboardLink(requireFirebaseUid()));
    }

    private static String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                    "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
