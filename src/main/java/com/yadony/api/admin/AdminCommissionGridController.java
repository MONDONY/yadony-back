package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.commission.CommissionSimulator;
import com.yadony.api.admin.dto.CommissionGridAdminResponse;
import com.yadony.api.admin.dto.CommissionGridListResponse;
import com.yadony.api.admin.dto.CommissionGridRequest;
import com.yadony.api.admin.dto.CommissionSimulationRequest;
import com.yadony.api.admin.dto.CommissionSimulationResponse;
import com.yadony.api.common.PageResponse;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridDraft;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionGridWarning;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Onglet « Tarification » : grilles de commission par paliers (spec 2026-10-18 §5.5). Réservé à
 * ADMIN/SUPER_ADMIN ({@code CONFIG_MANAGE}) : la grille dit toute l'économie de la plateforme.
 *
 * <p>Chaque méthode re-déclare l'expression COMPLÈTE : une {@code @PreAuthorize} de méthode
 * remplace celle de la classe. Les règles (validation, avertissement de hausse, audit_log) vivent
 * dans {@link CommissionGridService}, pas ici.
 */
@RestController
@RequestMapping("/admin/commission-grids")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
public class AdminCommissionGridController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CommissionGridService gridService;
    private final CommissionSimulator simulator;

    public AdminCommissionGridController(CommissionGridService gridService, CommissionSimulator simulator) {
        this.gridService = gridService;
        this.simulator = simulator;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @GetMapping
    public CommissionGridListResponse list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        // Requête native côté historique : jamais de tri ni le Pageable brut du client.
        var history = gridService.history(PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)));
        return new CommissionGridListResponse(
                gridService.current().map(CommissionGridAdminResponse::from).orElse(null),
                gridService.scheduled().map(CommissionGridAdminResponse::from).orElse(null),
                PageResponse.from(history.map(CommissionGridAdminResponse::from)));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PostMapping
    public ResponseEntity<CommissionGridAdminResponse> schedule(@RequestBody @Valid CommissionGridRequest request,
                                                                Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        CommissionGridDraft draft = request.toDraft();
        // Avertissements calculés AVANT la programmation : après, la nouvelle grille serait la référence.
        List<CommissionGridWarning> warnings = gridService.warningsFor(draft);
        CommissionGrid saved = gridService.schedule(draft, request.acknowledged(), adminId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommissionGridAdminResponse.from(saved).withWarnings(warnings));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, Authentication authentication) {
        gridService.cancel(id, AdminPrincipal.requireAdminId(authentication));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PostMapping("/simulate")
    public CommissionSimulationResponse simulate(@RequestBody @Valid CommissionSimulationRequest request) {
        return simulator.simulate(request);
    }
}
