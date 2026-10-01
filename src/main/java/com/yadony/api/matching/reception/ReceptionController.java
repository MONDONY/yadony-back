package com.yadony.api.matching.reception;

import com.yadony.api.matching.reception.dto.ReceptionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Colis attendus par l'utilisateur courant, en tant que destinataire. Tout rôle. */
@RestController
@RequestMapping("/receptions")
@PreAuthorize("isAuthenticated()")
public class ReceptionController {

    private final ReceptionService service;

    public ReceptionController(ReceptionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ReceptionResponse>> list(@AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(service.list(firebaseUid));
    }

    @GetMapping("/{bidId}")
    public ResponseEntity<ReceptionResponse> get(@AuthenticationPrincipal String firebaseUid,
                                                 @PathVariable UUID bidId) {
        return ResponseEntity.ok(service.get(bidId, firebaseUid));
    }

    @PostMapping("/{bidId}/confirm")
    public ResponseEntity<ReceptionResponse> confirm(@AuthenticationPrincipal String firebaseUid,
                                                     @PathVariable UUID bidId) {
        return ResponseEntity.ok(service.confirm(bidId, firebaseUid));
    }

    @PostMapping("/{bidId}/decline")
    public ResponseEntity<Void> decline(@AuthenticationPrincipal String firebaseUid,
                                        @PathVariable UUID bidId) {
        service.decline(bidId, firebaseUid);
        return ResponseEntity.noContent().build();
    }
}
