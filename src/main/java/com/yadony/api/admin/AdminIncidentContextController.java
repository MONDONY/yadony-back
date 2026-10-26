package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminIncidentContextResponse;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.NoShowReasons;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Contexte des litiges et des déclarations d'absence pour le poste de traitement Incidents (lecture seule). */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminIncidentContextController {

    private final DisputeRepository disputeRepo;
    private final CancellationRepository cancellationRepo;
    private final IncidentContextService contextService;

    public AdminIncidentContextController(DisputeRepository disputeRepo, CancellationRepository cancellationRepo,
                                          IncidentContextService contextService) {
        this.disputeRepo = disputeRepo;
        this.cancellationRepo = cancellationRepo;
        this.contextService = contextService;
    }

    @PreAuthorize("hasAuthority('DISPUTE_VIEW')")
    @GetMapping("/admin/disputes/{id}/context")
    public ResponseEntity<AdminIncidentContextResponse> disputeContext(@PathVariable UUID id) {
        return ResponseEntity.ok(contextService.forDispute(disputeRepo.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "dispute-not-found",
                        "Not Found", "Litige introuvable"))));
    }

    /** Seules les déclarations d'absence sont des incidents : une autre annulation reste introuvable ici. */
    @PreAuthorize("hasAuthority('DISPUTE_VIEW')")
    @GetMapping("/admin/cancellations/{id}/context")
    public ResponseEntity<AdminIncidentContextResponse> noShowContext(@PathVariable UUID id) {
        return ResponseEntity.ok(contextService.forCancellation(cancellationRepo.findById(id)
                .filter(c -> NoShowReasons.ALL.contains(c.getReason()))
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "noshow-not-found",
                        "Not Found", "Déclaration introuvable"))));
    }
}
