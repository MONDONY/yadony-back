package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.broadcast.AdminBroadcastEntity;
import com.yadony.api.admin.broadcast.AdminBroadcastRepository;
import com.yadony.api.admin.broadcast.BroadcastAudienceService;
import com.yadony.api.admin.broadcast.BroadcastService;
import com.yadony.api.admin.broadcast.BroadcastTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.broadcast.BroadcastTargetType;
import com.yadony.api.admin.broadcast.preset.AudiencePreset;
import com.yadony.api.admin.broadcast.preset.AudiencePresets;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository.RecipientRow;
import com.yadony.api.admin.broadcast.segment.BroadcastSegmentService;
import com.yadony.api.admin.dto.AdminBroadcastResponse;
import com.yadony.api.admin.dto.AudienceFieldResponse;
import com.yadony.api.admin.dto.BroadcastRecipientResponse;
import com.yadony.api.admin.dto.BroadcastTargetRequest.ResolvedTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    /** Borne haute de pagination, comme {@code AdminFinanceController}. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_PAGE_SIZE = 1000;

    private final BroadcastService broadcastService;
    private final BroadcastAudienceService audienceService;
    private final AdminBroadcastRepository broadcastRepository;
    private final AuditService auditService;
    private final BroadcastSegmentService segmentService;
    private final ObjectMapper objectMapper;
    private final int exportMaxRows;

    public AdminBroadcastController(BroadcastService broadcastService,
                                    BroadcastAudienceService audienceService,
                                    AdminBroadcastRepository broadcastRepository,
                                    AuditService auditService,
                                    BroadcastSegmentService segmentService,
                                    ObjectMapper objectMapper,
                                    @Value("${yadony.broadcast.export-max-rows:50000}") int exportMaxRows) {
        this.broadcastService = broadcastService;
        this.audienceService = audienceService;
        this.broadcastRepository = broadcastRepository;
        this.auditService = auditService;
        this.segmentService = segmentService;
        this.objectMapper = objectMapper;
        this.exportMaxRows = exportMaxRows;
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
        ResolvedTarget resolved = request.target().toDomain(segmentService::requireRule);
        BroadcastTarget target = resolved.target();

        // Ciblage nominatif : 404 si le compte n'existe pas, 422 s'il ne peut plus rien
        // recevoir. Sans cette garde, l'envoi partait « a 0 destinataire » en 202, et
        // l'historique affichait un envoi reussi que personne n'a recu.
        var targetUser = audienceService.targetUser(target);
        if (targetUser.isPresent() && !targetUser.get().reachable()) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "broadcast-user-not-reachable", "problem.admin.broadcast-user-not-reachable");
        }

        AdminBroadcastEntity saved = broadcastService.record(
                request.title(), request.body(), target, adminId,
                resolved.segmentId(), resolved.segmentName());

        // Les cles ci-dessous echappent toutes a la denylist PII d'AuditService.redact()
        // (aucune ne finit par « name » ni ne contient phone/email/city/label).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", saved.getTitle());
        payload.put("targetType", saved.getTargetType().name());
        payload.put("targetOrigin", saved.getTargetOrigin());
        payload.put("targetDestination", saved.getTargetDestination());
        payload.put("targetUserId", saved.getTargetUserId());
        payload.put("recipientCount", saved.getRecipientCount());
        payload.put("push", request.pushOrDefault());
        AudienceRule.Group audienceRule = ruleOf(target);
        payload.put("ruleHash", audienceRule == null ? null : audienceRule.hash());
        payload.put("conditionCount", audienceRule == null ? null : audienceRule.conditionCount());
        payload.put("segmentId", resolved.segmentId());
        auditService.log("admin_broadcast", saved.getId(), "BROADCAST_SENT", adminId, payload);

        broadcastService.dispatchAsync(saved.getId(), saved.getTitle(), saved.getBody(), target,
                request.pushOrDefault());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AdminBroadcastResponse.from(saved, objectMapper));
    }

    /** Apercu du volume avant envoi. Aucune ecriture, donc aucune entree audit_log. */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @PostMapping("/admin/notifications/broadcast/preview")
    public BroadcastAudienceResponse preview(@RequestBody @Valid BroadcastTargetRequest request) {
        BroadcastTarget target = request.toDomain(segmentService::requireRule).target();
        var targetUser = audienceService.targetUser(target);
        return new BroadcastAudienceResponse(audienceService.count(target),
                audienceService.countPushReachable(target),
                targetUser.map(BroadcastAudienceService.TargetUser::displayName).orElse(null),
                targetUser.map(BroadcastAudienceService.TargetUser::reachable).orElse(null));
    }

    /** Catalogue des champs ciblables, pour le constructeur de règles de l'admin. */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @GetMapping("/admin/notifications/broadcast/fields")
    public List<AudienceFieldResponse> fields() {
        return AudienceFieldResponse.catalogue();
    }

    /**
     * Audiences prêtes à l'emploi et leurs modèles de message. Catalogue fixe : il ne lit aucune
     * donnée d'utilisateur ; l'audience réelle ne se calcule qu'à l'aperçu ou à l'envoi.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @GetMapping("/admin/notifications/broadcast/presets")
    public List<AudiencePreset> presets() {
        return AudiencePresets.all();
    }

    /** Liste paginée des destinataires d'un ciblage (sans coordonnées). */
    // Ces routes exposent les noms des utilisateurs : les droits d'un admin pouvant etre surcharges
    // par compte (AdminPermissions.effective), NOTIFICATION_SEND seul ne suffit pas, USER_VIEW aussi.
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND') and hasAuthority('USER_VIEW')")
    @PostMapping("/admin/notifications/broadcast/recipients")
    public Page<BroadcastRecipientResponse> recipients(@RequestBody @Valid BroadcastTargetRequest request,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        BroadcastTarget target = request.toDomain(segmentService::requireRule).target();
        return audienceService.recipients(target, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))
                .map(BroadcastRecipientResponse::from);
    }

    /** Export CSV des destinataires, borné par {@code yadony.broadcast.export-max-rows}. */
    // Ces routes exposent les noms des utilisateurs : les droits d'un admin pouvant etre surcharges
    // par compte (AdminPermissions.effective), NOTIFICATION_SEND seul ne suffit pas, USER_VIEW aussi.
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND') and hasAuthority('USER_VIEW')")
    @PostMapping("/admin/notifications/broadcast/recipients.csv")
    public ResponseEntity<byte[]> recipientsCsv(@RequestBody @Valid BroadcastTargetRequest request,
                                                Authentication authentication) {
        UUID adminId = adminId(authentication);
        BroadcastTarget target = request.toDomain(segmentService::requireRule).target();
        long total = audienceService.count(target);
        if (total > exportMaxRows) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "broadcast-export-too-large", "problem.admin.broadcast-export-too-large", String.valueOf(exportMaxRows), String.valueOf(total));
        }
        List<RecipientRow> rows = new java.util.ArrayList<>();
        int pageNumber = 0;
        Page<RecipientRow> current;
        do {
            current = audienceService.recipients(target, pageNumber++, EXPORT_PAGE_SIZE);
            rows.addAll(current.getContent());
        } while (current.hasNext() && rows.size() < exportMaxRows);
        if (rows.size() > exportMaxRows) {
            rows = new java.util.ArrayList<>(rows.subList(0, exportMaxRows));
        }

        AudienceRule.Group rule = ruleOf(target);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleHash", rule == null ? null : rule.hash());
        payload.put("rowCount", rows.size());
        auditService.log("admin_broadcast", null, "BROADCAST_RECIPIENTS_EXPORTED", adminId, payload);

        String filename = "destinataires-"
                + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(BroadcastRecipientsCsv.write(rows));
    }

    /** Règle effective d'un ciblage ; nulle pour USER (un compte désigné n'est pas un comportement). */
    private static AudienceRule.Group ruleOf(BroadcastTarget target) {
        return target.type() == BroadcastTargetType.USER ? null : BroadcastAudienceService.equivalentRule(target);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @GetMapping("/admin/notifications/broadcasts")
    public Page<AdminBroadcastResponse> history(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        // Borne : une page d'ecran, pas un export deguise. Et un page negatif ferait lever
        // IllegalArgumentException a PageRequest.of, sans handler dedie — 500 au lieu d'un
        // 4xx RFC 7807.
        return broadcastRepository.findAllByOrderByCreatedAtDesc(
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)))
                .map(e -> AdminBroadcastResponse.from(e, objectMapper));
    }

    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "admin-principal-required", "problem.admin.admin-principal-required");
    }
}
