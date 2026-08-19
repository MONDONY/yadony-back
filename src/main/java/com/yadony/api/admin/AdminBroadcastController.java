package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.broadcast.AdminBroadcastEntity;
import com.yadony.api.admin.broadcast.AdminBroadcastRepository;
import com.yadony.api.admin.broadcast.BroadcastAudienceService;
import com.yadony.api.admin.broadcast.BroadcastService;
import com.yadony.api.admin.broadcast.BroadcastTarget;
import com.yadony.api.admin.dto.AdminBroadcastResponse;
import com.yadony.api.admin.dto.BroadcastAudienceResponse;
import com.yadony.api.admin.dto.BroadcastRequest;
import com.yadony.api.admin.dto.BroadcastTargetRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lot D — broadcast de notifications. Reserve a ADMIN/SUPER_ADMIN : SUPPORT ne recoit
 * pas NOTIFICATION_SEND.
 *
 * <p>⚠️ Chaque methode re-declare l'expression COMPLETE : une {@code @PreAuthorize} de
 * methode remplace celle de la classe, elle ne s'y ajoute pas. Et {@code hasRole('ADMIN')}
 * ne discrimine personne — tout compte admin la porte, SUPPORT compris : seule l'authority
 * filtre reellement.
 */
@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
public class AdminBroadcastController {

    private final BroadcastService broadcastService;
    private final BroadcastAudienceService audienceService;
    private final AdminBroadcastRepository broadcastRepository;
    private final AuditService auditService;

    public AdminBroadcastController(BroadcastService broadcastService,
                                    BroadcastAudienceService audienceService,
                                    AdminBroadcastRepository broadcastRepository,
                                    AuditService auditService) {
        this.broadcastService = broadcastService;
        this.audienceService = audienceService;
        this.broadcastRepository = broadcastRepository;
        this.auditService = auditService;
    }

    /**
     * 202 Accepted : le comptage et l'historisation sont faits, la diffusion ne l'est pas
     * encore. Repondre 200 laisserait croire que tout le monde a deja recu le message.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @PostMapping("/admin/notifications/broadcast")
    public ResponseEntity<AdminBroadcastResponse> send(@RequestBody @Valid BroadcastRequest request,
                                                       Authentication authentication) {
        UUID adminId = adminId(authentication);
        BroadcastTarget target = request.target().toDomain();

        AdminBroadcastEntity saved = broadcastService.record(
                request.title(), request.body(), target, adminId);

        // Les cles ci-dessous echappent toutes a la denylist PII d'AuditService.redact()
        // (aucune ne finit par « name » ni ne contient phone/email/city/label).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", saved.getTitle());
        payload.put("targetType", saved.getTargetType().name());
        payload.put("targetOrigin", saved.getTargetOrigin());
        payload.put("targetDestination", saved.getTargetDestination());
        payload.put("targetUserId", saved.getTargetUserId());
        payload.put("recipientCount", saved.getRecipientCount());
        auditService.log("admin_broadcast", saved.getId(), "BROADCAST_SENT", adminId, payload);

        broadcastService.dispatchAsync(saved.getId(), saved.getTitle(), saved.getBody(), target);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AdminBroadcastResponse.from(saved));
    }

    /** Apercu du volume avant envoi. Aucune ecriture, donc aucune entree audit_log. */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @PostMapping("/admin/notifications/broadcast/preview")
    public BroadcastAudienceResponse preview(@RequestBody @Valid BroadcastTargetRequest request) {
        return new BroadcastAudienceResponse(audienceService.count(request.toDomain()));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @GetMapping("/admin/notifications/broadcasts")
    public Page<AdminBroadcastResponse> history(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return broadcastRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(AdminBroadcastResponse::from);
    }

    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
