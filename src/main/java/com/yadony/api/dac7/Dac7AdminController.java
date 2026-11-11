package com.yadony.api.dac7;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.dac7.dto.Dac7AnomalyRow;
import com.yadony.api.dac7.dto.Dac7DecisionRequest;
import com.yadony.api.dac7.dto.Dac7DiligenceEntry;
import com.yadony.api.dac7.dto.Dac7ExportRequest;
import com.yadony.api.dac7.dto.Dac7OverviewResponse;
import com.yadony.api.dac7.dto.Dac7RefundRow;
import com.yadony.api.dac7.dto.Dac7SellerRow;
import com.yadony.api.dac7.dto.Dac7StatementsRequest;
import com.yadony.api.dac7.dto.Dac7StatementsResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Admin DAC7 (contrat 2, consommé par le lot 3c), droit {@code DAC7_REPORT} (ADMIN oui, SUPPORT non). Chaque méthode
 * re-déclare {@code hasRole('ADMIN')} : une {@code @PreAuthorize} de méthode remplace celle de la classe. Aucun de ces
 * endpoints n'envoie quoi que ce soit à la DGFiP : l'export rend un fichier, le dépôt Télé-TD reste un geste humain.
 */
@RestController
@RequestMapping("/admin/dac7")
@PreAuthorize("hasRole('ADMIN')")
public class Dac7AdminController {

    static final String EXCLUDED_HEADER = "X-Dac7-Excluded";
    static final String ENCRYPTED_HEADER = "X-Dac7-Encrypted";

    private final Dac7AdminService admin;
    private final Dac7ExportService exports;
    private final Dac7SellerStatementService statements;

    public Dac7AdminController(Dac7AdminService admin, Dac7ExportService exports,
                               Dac7SellerStatementService statements) {
        this.admin = admin;
        this.exports = exports;
        this.statements = statements;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @GetMapping("/overview")
    public Dac7OverviewResponse overview(@RequestParam(required = false) Integer year) {
        return admin.overview(year);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @GetMapping("/sellers")
    public List<Dac7SellerRow> sellers(@RequestParam(required = false) Integer year,
                                       @RequestParam(required = false) String filter) {
        return admin.sellers(year, filter);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @GetMapping("/sellers/{userId}/diligence")
    public List<Dac7DiligenceEntry> diligence(@PathVariable UUID userId) {
        return admin.diligence(userId);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @PostMapping("/sellers/{userId}/remind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remind(@PathVariable UUID userId, Authentication authentication) {
        admin.remind(userId, AdminPrincipal.requireAdminId(authentication));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @PostMapping("/sellers/{userId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decision(@PathVariable UUID userId, @RequestBody Dac7DecisionRequest request,
                         Authentication authentication) {
        admin.decision(userId, AdminPrincipal.requireAdminId(authentication), request.note());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @GetMapping("/refunds")
    public List<Dac7RefundRow> refunds(@RequestParam(required = false) Integer year) {
        return admin.refunds(year);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @GetMapping("/anomalies")
    public List<Dac7AnomalyRow> anomalies(@RequestParam(required = false) Integer year) {
        return admin.anomalies(year);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @PostMapping("/exports")
    public ResponseEntity<byte[]> export(@RequestBody Dac7ExportRequest request, Authentication authentication) {
        Dac7ExportService.ExportFile file = exports.export(request.year(), request.mode(), request.sequence(),
                AdminPrincipal.requireAdminId(authentication));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header(EXCLUDED_HEADER, String.valueOf(file.excluded()))
                .header(ENCRYPTED_HEADER, String.valueOf(file.encrypted()))
                .body(file.content());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @GetMapping("/exports/control.csv")
    public ResponseEntity<byte[]> controlCsv(@RequestParam(required = false) Integer year,
                                             Authentication authentication) {
        String csv = exports.controlCsv(year, AdminPrincipal.requireAdminId(authentication));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("dac7-controle-" + year + ".csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('DAC7_REPORT')")
    @PostMapping("/statements/send")
    public Dac7StatementsResponse sendStatements(@RequestBody Dac7StatementsRequest request,
                                                 Authentication authentication) {
        return statements.send(request.year(), AdminPrincipal.requireAdminId(authentication));
    }
}
