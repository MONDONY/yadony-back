package com.yadony.api.maintenance;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.maintenance.dto.MaintenanceStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * État du mode maintenance, lu par l'application au démarrage, au retour au premier plan
 * et quand le document Firestore {@code config/maintenance} change. Public (sous
 * {@code /config/**}) et jamais bloqué par {@link MaintenanceFilter} ; avec un jeton,
 * {@code exempt} dit si cet appelant passe malgré la maintenance.
 */
@RestController
@RequestMapping("/config/maintenance")
public class MaintenanceController {

    private final MaintenanceCache cache;

    public MaintenanceController(MaintenanceCache cache) {
        this.cache = cache;
    }

    @GetMapping
    public ResponseEntity<MaintenanceStatusResponse> status(Authentication authentication,
                                                            HttpServletRequest request) {
        MaintenanceSnapshot snapshot = cache.snapshot();
        boolean exempt = authentication != null && (AdminPrincipal.isAdminCaller(authentication, request)
                || authentication.getPrincipal() instanceof String uid && snapshot.isExempt(uid));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new MaintenanceStatusResponse(snapshot.enabled(), snapshot.message(),
                        snapshot.estimatedEndAt(), snapshot.enabled() && exempt));
    }
}
