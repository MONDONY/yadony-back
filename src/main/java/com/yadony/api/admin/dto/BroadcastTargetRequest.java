package com.yadony.api.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.admin.broadcast.BroadcastTarget;
import com.yadony.api.admin.broadcast.BroadcastTargetType;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.AudienceSqlCompiler;
import com.yadony.api.admin.broadcast.segment.BroadcastSegmentService.SegmentSnapshot;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Ciblage tel qu'il arrive du front. La coherence (corridor complet, userId present) est
 * validee par {@link BroadcastTarget}, pas ici : une seule regle, un seul endroit.
 *
 * <p>Avec {@code type = RULE}, exactement un de {@code rule} / {@code segmentId}. Pour les
 * cinq anciens types, les deux sont ignores (ancien contrat inchange).
 */
public record BroadcastTargetRequest(
        @NotNull BroadcastTargetType type,
        String origin,
        String destination,
        UUID userId,
        JsonNode rule,
        UUID segmentId) {

    /** Résout un segment enregistré (404 s'il est supprimé). */
    @FunctionalInterface
    public interface SegmentResolver {
        SegmentSnapshot resolve(UUID segmentId);
    }

    public record ResolvedTarget(BroadcastTarget target, UUID segmentId, String segmentName) {}

    public ResolvedTarget toDomain(SegmentResolver resolver) {
        if (type != BroadcastTargetType.RULE) {
            return new ResolvedTarget(new BroadcastTarget(type, origin, destination, userId, null), null, null);
        }
        boolean hasRule = rule != null && !rule.isNull() && !rule.isMissingNode();
        if (hasRule == (segmentId != null)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "broadcast-target-invalid", "problem.admin.broadcast-target-invalid");
        }
        if (hasRule) {
            AudienceRule.Group parsed = AudienceRule.parse(rule);
            AudienceSqlCompiler.compile(parsed);
            return new ResolvedTarget(new BroadcastTarget(type, null, null, null, parsed), null, null);
        }
        SegmentSnapshot snapshot = resolver.resolve(segmentId);
        return new ResolvedTarget(new BroadcastTarget(type, null, null, null, snapshot.rule()),
                snapshot.id(), snapshot.name());
    }
}
