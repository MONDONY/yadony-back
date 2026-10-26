package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminAlertContextResponse;
import com.yadony.api.admin.dto.AdminAlertResponse;
import com.yadony.api.admin.dto.AdminAlertViolationsResponse;
import com.yadony.api.admin.dto.AlertBatchDtos.ReopenAlertRequest;
import com.yadony.api.admin.dto.AlertBatchDtos.ResolveBatchRequest;
import com.yadony.api.admin.dto.AlertBatchDtos.ResolveBatchResponse;
import com.yadony.api.admin.dto.AlertBatchDtos.SnoozeAlertRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/admin/alerts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAlertController {

    /** Préfixe des alertes levées par {@link MoneyIntegrityMonitor}, suivi du code de la règle. */
    static final String MONEY_INVARIANT_PREFIX = "MONEY_INVARIANT_";

    static final int BATCH_MAX = 100;
    static final int NOTE_MIN = 3;
    static final int NOTE_MAX = 500;
    static final int SNOOZE_MAX_HOURS = 168;

    private final AdminAlertRepository alertRepo;
    private final AuditService auditService;
    private final MoneyIntegrityMonitor moneyIntegrityMonitor;
    private final AlertContextService contextService;

    public AdminAlertController(AdminAlertRepository alertRepo, AuditService auditService,
                                MoneyIntegrityMonitor moneyIntegrityMonitor, AlertContextService contextService) {
        this.alertRepo = alertRepo;
        this.auditService = auditService;
        this.moneyIntegrityMonitor = moneyIntegrityMonitor;
        this.contextService = contextService;
    }

    @PreAuthorize("hasAuthority('ALERT_VIEW')")
    @GetMapping
    public ResponseEntity<Page<AdminAlertResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) Boolean snoozed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AdminAlertResponse> result = alertRepo
                .findFiltered(type, severity, resolved, snoozed, OffsetDateTime.now(ZoneOffset.UTC),
                        PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(AdminAlertResponse::from);
        return ResponseEntity.ok(result);
    }

    /** Une alerte, pour le lien « voir l'alerte » d'une autre fiche (paiement…). */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('ALERT_VIEW')")
    @GetMapping("/{id}")
    public ResponseEntity<AdminAlertResponse> get(@PathVariable UUID id) {
        return alertRepo.findById(id)
                .map(AdminAlertResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "alert-not-found", "Not Found", "Alerte introuvable"));
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

    /** Autres alertes sur le même paiement ou colis, et gestes tracés sur celle-ci. */
    @PreAuthorize("hasAuthority('ALERT_VIEW')")
    @GetMapping("/{id}/context")
    public ResponseEntity<AdminAlertContextResponse> context(@PathVariable UUID id) {
        return ResponseEntity.ok(contextService.build(find(id)));
    }

    /**
     * Résout plusieurs alertes avec une même note (le poste de traitement résout un groupe d'un coup).
     * Chacune garde sa ligne d'audit ; celles déjà résolues ou inconnues sont rendues à part, rien
     * n'échoue en bloc.
     */
    @PreAuthorize("hasAuthority('ALERT_RESOLVE')")
    @PostMapping("/resolve-batch")
    @Transactional
    public ResponseEntity<ResolveBatchResponse> resolveBatch(
            @RequestBody ResolveBatchRequest request,
            Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        String note = requireNote(request != null ? request.note() : null);
        List<UUID> ids = request != null && request.ids() != null
                ? new ArrayList<>(new LinkedHashSet<>(request.ids())) : List.of();
        if (ids.isEmpty() || ids.size() > BATCH_MAX) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "alert-batch-invalid",
                    "Unprocessable Entity", "Indiquez entre 1 et " + BATCH_MAX + " alertes");
        }
        List<UUID> resolved = new ArrayList<>();
        List<UUID> already = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            AdminAlertEntity alert = id == null ? null : alertRepo.findById(id).orElse(null);
            if (alert == null) {
                missing.add(id);
            } else if (alert.isResolved()) {
                already.add(id);
            } else {
                alert.setResolved(true);
                alert.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
                alertRepo.save(alert);
                auditService.log("ADMIN_ALERT", id, "ADMIN_ALERT_RESOLVED", adminId, auditPayload(alert, note, "batch", "true"));
                resolved.add(id);
            }
        }
        return ResponseEntity.ok(new ResolveBatchResponse(resolved, already, missing));
    }

    /**
     * Rouvre une alerte résolue (résolue trop vite, ou l'anomalie est revenue). Refusé s'il existe déjà
     * une alerte ouverte du même type : les schedulers n'en lèvent qu'une à la fois.
     */
    @PreAuthorize("hasAuthority('ALERT_RESOLVE')")
    @PostMapping("/{id}/reopen")
    @Transactional
    public ResponseEntity<AdminAlertResponse> reopen(
            @PathVariable UUID id,
            @RequestBody ReopenAlertRequest request,
            Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        String note = requireNote(request != null ? request.note() : null);
        AdminAlertEntity alert = find(id);
        if (!alert.isResolved()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "alert-not-resolved",
                    "Conflict", "Cette alerte n'est pas résolue");
        }
        if (!alertRepo.findByTypeAndResolved(alert.getType(), false).isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "alert-already-open-for-type",
                    "Conflict", "Une alerte du même type est déjà ouverte");
        }
        alert.setResolved(false);
        alert.setResolvedAt(null);
        alert.setSnoozedUntil(null);
        alertRepo.save(alert);
        auditService.log("ADMIN_ALERT", id, "ADMIN_ALERT_REOPENED", adminId, auditPayload(alert, note));
        return ResponseEntity.ok(AdminAlertResponse.from(alert));
    }

    /** Reporte une alerte ouverte de 1 à 168 h : elle quitte la file « ouvertes » puis y revient. */
    @PreAuthorize("hasAuthority('ALERT_RESOLVE')")
    @PostMapping("/{id}/snooze")
    @Transactional
    public ResponseEntity<AdminAlertResponse> snooze(
            @PathVariable UUID id,
            @RequestBody SnoozeAlertRequest request,
            Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        String note = requireNote(request != null ? request.note() : null);
        Integer hours = request != null ? request.hours() : null;
        if (hours == null || hours < 1 || hours > SNOOZE_MAX_HOURS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "alert-snooze-invalid",
                    "Unprocessable Entity", "Le report va de 1 à " + SNOOZE_MAX_HOURS + " heures");
        }
        AdminAlertEntity alert = find(id);
        if (alert.isResolved()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "alert-already-resolved",
                    "Conflict", "Cette alerte est déjà résolue");
        }
        alert.setSnoozedUntil(OffsetDateTime.now(ZoneOffset.UTC).plusHours(hours));
        alertRepo.save(alert);
        auditService.log("ADMIN_ALERT", id, "ADMIN_ALERT_SNOOZED", adminId, auditPayload(alert, note, "hours", String.valueOf(hours)));
        return ResponseEntity.ok(AdminAlertResponse.from(alert));
    }

    /** Remet une alerte reportée dans la file « ouvertes » sans attendre la fin du report. */
    @PreAuthorize("hasAuthority('ALERT_RESOLVE')")
    @DeleteMapping("/{id}/snooze")
    @Transactional
    public ResponseEntity<AdminAlertResponse> unsnooze(@PathVariable UUID id, Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        AdminAlertEntity alert = find(id);
        alert.setSnoozedUntil(null);
        alertRepo.save(alert);
        auditService.log("ADMIN_ALERT", id, "ADMIN_ALERT_UNSNOOZED", adminId, auditPayload(alert, ""));
        return ResponseEntity.ok(AdminAlertResponse.from(alert));
    }

    private AdminAlertEntity find(UUID id) {
        return alertRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "alert-not-found", "Not Found", "Alerte introuvable"));
    }

    /** Note des gestes ajoutés par le poste de traitement : 3 à 500 caractères, trace d'audit. */
    private static String requireNote(String raw) {
        String note = raw == null ? "" : raw.trim();
        if (note.length() < NOTE_MIN || note.length() > NOTE_MAX) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "alert-note-invalid",
                    "Unprocessable Entity", "La note doit faire entre " + NOTE_MIN + " et " + NOTE_MAX + " caractères");
        }
        return note;
    }

    private static Map<String, Object> auditPayload(AdminAlertEntity alert, String note, String... extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("alertType", Objects.toString(alert.getType(), ""));
        payload.put("severity", Objects.toString(alert.getSeverity(), ""));
        payload.put("note", note);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            payload.put(extra[i], extra[i + 1]);
        }
        return payload;
    }
}
