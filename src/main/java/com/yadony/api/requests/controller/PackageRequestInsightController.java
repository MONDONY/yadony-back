package com.yadony.api.requests.controller;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.requests.dto.PackageRequestInsightsResponse;
import com.yadony.api.requests.dto.PackageRequestInvitationRequest;
import com.yadony.api.requests.dto.PackageRequestInvitationResponse;
import com.yadony.api.requests.service.PackageRequestInsightService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Vues et invitations d'une demande, réservées à son expéditeur. */
@RestController
@RequestMapping("/package-requests/{id}")
public class PackageRequestInsightController {

    private final PackageRequestInsightService service;
    private final UserRepository userRepository;

    public PackageRequestInsightController(PackageRequestInsightService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping("/insights")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestInsightsResponse insights(@PathVariable UUID id) {
        return service.getInsights(requireUserId(), id);
    }

    /** 201 à la première invitation de ce trajet, 200 si elle existait déjà (aucun push renvoyé). */
    @PostMapping("/invitations")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<PackageRequestInvitationResponse> invite(
            @PathVariable UUID id, @RequestBody @Valid PackageRequestInvitationRequest body) {
        var result = service.invite(requireUserId(), id, body.announcementId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.invitation());
    }

    private UUID requireUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis");
        }
        return userRepository.findByFirebaseUid((String) auth.getPrincipal())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }
}
