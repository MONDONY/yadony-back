package com.yadony.api.admin.broadcast.segment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.broadcast.BroadcastAudienceService;
import com.yadony.api.admin.broadcast.BroadcastTarget;
import com.yadony.api.admin.broadcast.BroadcastTargetType;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.AudienceSqlCompiler;
import com.yadony.api.admin.dto.BroadcastSegmentRequest;
import com.yadony.api.admin.dto.BroadcastSegmentResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** CRUD des segments d'audience. La règle est validée (forme + sens) avant tout enregistrement. */
@Service
public class BroadcastSegmentService {

    /** Règle déjà validée, copiée au moment de l'envoi. */
    public record SegmentSnapshot(UUID id, String name, AudienceRule.Group rule) {}

    private final BroadcastSegmentRepository repository;
    private final BroadcastAudienceService audienceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public BroadcastSegmentService(BroadcastSegmentRepository repository,
                                   BroadcastAudienceService audienceService,
                                   AuditService auditService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.audienceService = audienceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BroadcastSegmentResponse create(BroadcastSegmentRequest req, UUID adminId) {
        AudienceRule.Group rule = validate(req.rule());
        String name = req.name().trim();
        if (repository.existsByNameIgnoreCase(name)) {
            throw nameTaken();
        }
        BroadcastSegmentEntity saved = saveMappingConflict(
                new BroadcastSegmentEntity(name, blankToNull(req.description()), rule.canonicalJson(), adminId));
        audit("BROADCAST_SEGMENT_CREATED", saved.getId(), rule, adminId);
        return toResponse(saved, -1);
    }

    @Transactional
    public BroadcastSegmentResponse update(UUID id, BroadcastSegmentRequest req, UUID adminId) {
        BroadcastSegmentEntity entity = require(id);
        AudienceRule.Group rule = validate(req.rule());
        String name = req.name().trim();
        if (repository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw nameTaken();
        }
        entity.update(name, blankToNull(req.description()), rule.canonicalJson(), adminId);
        BroadcastSegmentEntity saved = saveMappingConflict(entity);
        audit("BROADCAST_SEGMENT_UPDATED", saved.getId(), rule, adminId);
        return toResponse(saved, -1);
    }

    @Transactional
    public void delete(UUID id, UUID adminId) {
        BroadcastSegmentEntity entity = require(id);
        AudienceRule.Group rule = parseStored(entity.getRule());
        entity.softDelete();
        repository.saveAndFlush(entity);
        audit("BROADCAST_SEGMENT_DELETED", id, rule, adminId);
    }

    @Transactional(readOnly = true)
    public BroadcastSegmentResponse get(UUID id) {
        BroadcastSegmentEntity entity = require(id);
        AudienceRule.Group rule = parseStored(entity.getRule());
        long count = audienceService.count(
                new BroadcastTarget(BroadcastTargetType.RULE, null, null, null, rule));
        return toResponse(entity, count);
    }

    @Transactional(readOnly = true)
    public Page<BroadcastSegmentResponse> list(Pageable pageable) {
        return repository.findAllByOrderByUpdatedAtDesc(pageable).map(e -> toResponse(e, -1));
    }

    @Transactional(readOnly = true)
    public SegmentSnapshot requireRule(UUID id) {
        BroadcastSegmentEntity entity = require(id);
        return new SegmentSnapshot(entity.getId(), entity.getName(), parseStored(entity.getRule()));
    }

    // ── interne ───────────────────────────────────────────────────────────────

    private BroadcastSegmentEntity require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "broadcast-segment-not-found", "Not Found",
                "Segment introuvable"));
    }

    /** Forme (parse) puis sens (compilation) : même double contrôle que l'envoi. */
    private AudienceRule.Group validate(JsonNode json) {
        AudienceRule.Group rule = AudienceRule.parse(json);
        AudienceSqlCompiler.compile(rule);
        return rule;
    }

    private AudienceRule.Group parseStored(String canonicalJson) {
        try {
            return AudienceRule.parse(objectMapper.readTree(canonicalJson));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Règle de segment illisible", e);
        }
    }

    private BroadcastSegmentEntity saveMappingConflict(BroadcastSegmentEntity entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // Course : deux écritures simultanées sur le même nom passent la pré-vérification.
            throw nameTaken();
        }
    }

    private static YadonyBusinessException nameTaken() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "broadcast-segment-name-taken",
                "Conflict", "Un segment porte déjà ce nom");
    }

    private void audit(String action, UUID id, AudienceRule.Group rule, UUID adminId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleHash", rule.hash());
        payload.put("conditionCount", rule.conditionCount());
        auditService.log("broadcast_segment", id, action, adminId, payload);
    }

    private BroadcastSegmentResponse toResponse(BroadcastSegmentEntity e, long recipientCount) {
        try {
            return new BroadcastSegmentResponse(e.getId(), e.getName(), e.getDescription(),
                    objectMapper.readTree(e.getRule()), recipientCount, e.getUpdatedAt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Règle de segment illisible", ex);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
