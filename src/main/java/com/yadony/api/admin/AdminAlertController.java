package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminAlertResponse;
import com.yadony.api.admin.dto.AdminAlertViolationsResponse;
import com.yadony.api.admin.dto.ResolveAlertRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.integrity.MoneyIntegrityMonitor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/admin/alerts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAlertController {

    /** Préfixe des alertes levées par {@link MoneyIntegrityMonitor}, suivi du code de la règle. */
    static final String MONEY_INVARIANT_PREFIX = "MONEY_INVARIANT_";

    private final AdminAlertRepository alertRepo;
    private final AuditService auditService;
    private final MoneyIntegrityMonitor moneyIntegrityMonitor;

    public AdminAlertController(AdminAlertRepository alertRepo, AuditService auditService,
                                MoneyIntegrityMonitor moneyIntegrityMonitor) {
        this.alertRepo = alertRepo;
        this.auditService = auditService;
        this.moneyIntegrityMonitor = moneyIntegrityMonitor;
    }

    @PreAuthorize("hasAuthority('ALERT_VIEW')")
    @GetMapping
    public ResponseEntity<Page<AdminAlertResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AdminAlertResponse> result = alertRepo
                .findFiltered(type, severity, resolved,
                        PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(AdminAlertResponse::from);
        return ResponseEntity.ok(result);
    }

    /**
     * Lignes ACTUELLEMENT en faute pour une alerte de cohérence de l'argent : la règle est
     * ré-exécutée maintenant (lecture seule), pour que l'admin voie quels paiements, colis ou
     * wallets corriger — et si l'anomalie a disparu avant de résoudre l'alerte.
     */
    @PreAuthorize("hasAuthority('ALERT_VIEW')")
    @GetMapping("/{id}/violations")
    public ResponseEntity<AdminAlertViolationsResponse> violations(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit) {
        AdminAlertEntity alert = alertRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "alert-not-found", "Not Found", "Alerte introuvable"));
        String type = Objects.toString(alert.getType(), "");
        if (!type.startsWith(MONEY_INVARIANT_PREFIX)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "alert-without-violations",
                    "Unprocessable Entity", "Cette alerte n'est pas liée à une règle de cohérence de l'argent");
        }
        String code = type.substring(MONEY_INVARIANT_PREFIX.length());
        return moneyIntegrityMonitor.inspect(code, limit)
                .map(AdminAlertViolationsResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "invariant-not-found",
                        "Not Found", "Règle de cohérence " + code + " inconnue"));
    }

    @PreAuthorize("hasAuthority('ALERT_RESOLVE')")
    @PostMapping("/{id}/resolve")
    @Transactional
    public ResponseEntity<AdminAlertResponse> resolve(
            @PathVariable UUID id,
            @RequestBody ResolveAlertRequest request,
            Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        AdminAlertEntity alert = alertRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "alert-not-found", "Not Found", "Alerte introuvable"));
        alert.setResolved(true);
        alert.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        alertRepo.save(alert);
        // La note n'a pas de colonne dans admin_alerts : l'audit est sa seule trace, avec
        // l'admin qui a clos l'alerte.
        auditService.log("ADMIN_ALERT", id, "ADMIN_ALERT_RESOLVED", adminId,
                Map.of("alertType", Objects.toString(alert.getType(), ""),
                        "severity", Objects.toString(alert.getSeverity(), ""),
                        "note", request != null ? Objects.toString(request.note(), "") : ""));
        return ResponseEntity.ok(AdminAlertResponse.from(alert));
    }
}
