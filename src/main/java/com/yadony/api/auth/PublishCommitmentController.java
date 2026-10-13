package com.yadony.api.auth;

import com.yadony.api.auth.dto.PublishCommitmentAcceptRequest;
import com.yadony.api.auth.dto.PublishCommitmentsResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Engagements de publication de l'utilisateur courant (écran avant la première publication). */
@RestController
@RequestMapping("/auth/me/publish-commitments")
public class PublishCommitmentController {

    private final PublishCommitmentService service;
    private final AuthService authService;

    public PublishCommitmentController(PublishCommitmentService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<PublishCommitmentsResponse> get() {
        return ResponseEntity.ok(service.get(authService.requireUserId()));
    }

    @PutMapping
    public ResponseEntity<Void> accept(@Valid @RequestBody PublishCommitmentAcceptRequest request) {
        service.accept(authService.requireUserId(), request.role(), request.version());
        return ResponseEntity.noContent().build();
    }
}
