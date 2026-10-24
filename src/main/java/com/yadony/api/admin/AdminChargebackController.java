package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminChargebackCountsResponse;
import com.yadony.api.admin.dto.AdminChargebackResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Litiges bancaires du back-office, en lecture seule. ⚠️ Une {@code @PreAuthorize} de méthode <b>remplace</b>
 * celle de la classe : chaque méthode re-déclare l'expression complète.
 */
@RestController
@RequestMapping("/admin/chargebacks")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
public class AdminChargebackController {

    /** Borne haute de pagination : une page d'écran, pas un export déguisé. */
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminFinanceListings listings;

    public AdminChargebackController(AdminFinanceListings listings) {
        this.listings = listings;
    }

    /**
     * Litiges, ouverts le plus récemment d'abord : vue ({@code ALL} par défaut, {@code OPEN}, {@code WON},
     * {@code LOST}) et recherche {@code q}. Une vue inconnue répond 400.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping
    public ResponseEntity<Page<AdminChargebackResponse>> list(
            @RequestParam(required = false) AdminChargebackView view,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(listings.chargebacks(view, q,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }

    /** Nombres par statut : même {@code q} que la liste, jamais la vue. */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/counts")
    public ResponseEntity<AdminChargebackCountsResponse> counts(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(listings.chargebackCounts(q));
    }
}
