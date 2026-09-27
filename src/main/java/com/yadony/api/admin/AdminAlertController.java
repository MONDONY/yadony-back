package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminAlertResponse;
import com.yadony.api.admin.dto.ResolveAlertRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
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

    private final AdminAlertRepository alertRepo;
    private final AuditService auditService;

    public AdminAlertController(AdminAlertRepository alertRepo, AuditService auditService) {
        this.alertRepo = alertRepo;
        this.auditService = auditService;
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
