package com.yadony.api.activation;

import com.yadony.api.activation.dto.ActivationResponse;
import com.yadony.api.activation.dto.DeclareIntentRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Guidage après KYC : statut d'activation et intention de l'utilisateur connecté. */
@RestController
@RequestMapping("/users/me")
public class ActivationController {

    private final ActivationService service;

    public ActivationController(ActivationService service) {
        this.service = service;
    }

    @GetMapping("/activation")
    public ResponseEntity<ActivationResponse> getActivation(Authentication authentication) {
        return ResponseEntity.ok(service.getActivation(authentication.getName()));
    }

    @PutMapping("/intent")
    public ResponseEntity<ActivationResponse> declareIntent(Authentication authentication,
                                                            @Valid @RequestBody DeclareIntentRequest request) {
        return ResponseEntity.ok(service.declareIntent(authentication.getName(), request));
    }
}
