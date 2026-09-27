package com.yadony.api.admin.export;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Exports CSV du back-office admin (conformité / comptabilité).
 * GET /admin/exports/{type} — type ∈ transactions | users | disputes | payouts.
 */
@RestController
@RequestMapping("/admin/exports")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('EXPORT_RUN')")
public class AdminExportController {

    private final AdminExportService exportService;
    private final AuditService auditService;

    public AdminExportController(AdminExportService exportService, AuditService auditService) {
        this.exportService = exportService;
        this.auditService = auditService;
    }

    @GetMapping("/{type}")
    public ResponseEntity<byte[]> download(
            @PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {

        // Résolu avant l'export : un export de données sans acteur identifié n'a pas lieu.
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        byte[] content = switch (type) {
            case "transactions" -> exportService.exportTransactions(from, to);
            case "users" -> exportService.exportUsers(from, to);
            case "disputes" -> exportService.exportDisputes(from, to);
            case "payouts" -> exportService.exportPayouts(from, to);
            default -> throw new YadonyBusinessException(
                    HttpStatus.BAD_REQUEST, "invalid-export-type", "Invalid Type",
                    "Type d'export invalide. Valeurs acceptées : transactions, users, disputes, payouts.");
        };

        auditService.log("EXPORT", null, "EXPORT_RUN", adminId, Map.of(
                "type", type,
                "from", Objects.toString(from, ""),
                "to", Objects.toString(to, "")));

        String filename = type
                + (from != null ? "_" + from : "")
                + (to != null ? "_" + to : "")
                + ".csv";

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }
}
