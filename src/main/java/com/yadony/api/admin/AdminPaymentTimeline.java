package com.yadony.api.admin;

import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.payments.PaymentEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Chronologie d'un paiement pour le back-office : les dates portées par le paiement (création,
 * capture, libération) et ses entrées du journal d'audit ({@code entity_type = 'PAYMENT'}), avec
 * l'auteur résolu (admin par son e-mail, utilisateur par son nom).
 */
@Component
public class AdminPaymentTimeline {

    private final AuditLogRepository auditLogRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminPaymentInsights insights;

    public AdminPaymentTimeline(AuditLogRepository auditLogRepository, NamedParameterJdbcTemplate jdbc,
                                AdminPaymentInsights insights) {
        this.auditLogRepository = auditLogRepository;
        this.jdbc = jdbc;
        this.insights = insights;
    }

    /**
     * Une étape de la vie du paiement.
     *
     * @param action    code de l'action ({@code PAYMENT_CREATED}, {@code ESCROW_FORCE_RELEASED}…)
     * @param source    {@code PAYMENT} (date portée par le paiement) ou {@code AUDIT}
     * @param actorKind {@code ADMIN}, {@code USER} ou {@code null} (système, inconnu)
     */
    public record Entry(LocalDateTime at, String action, String source, UUID actorId, String actorKind,
                        String actorLabel, Map<String, Object> payload) {
    }

    public List<Entry> of(PaymentEntity payment) {
        List<Entry> entries = new ArrayList<>();
        milestone(entries, payment.getCreatedAt(), "PAYMENT_CREATED");
        milestone(entries, toUtc(payment.getCapturedAt()), "PAYMENT_CAPTURED");
        milestone(entries, payment.getEscrowReleasedAt(), "ESCROW_RELEASED");

        List<AuditLogEntity> audit = auditLogRepository
                .findTop200ByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("PAYMENT", payment.getId());
        Set<UUID> actorIds = new LinkedHashSet<>();
        audit.forEach(a -> { if (a.getActorId() != null) actorIds.add(a.getActorId()); });
        Map<UUID, String> admins = adminEmailsOf(actorIds);
        Map<UUID, String> users = insights.namesOf(actorIds.stream().filter(id -> !admins.containsKey(id)).toList());
        for (AuditLogEntity a : audit) {
            UUID actor = a.getActorId();
            String kind = actor == null ? null : admins.containsKey(actor) ? "ADMIN" : users.containsKey(actor) ? "USER" : null;
            String label = actor == null ? null : admins.containsKey(actor) ? admins.get(actor) : users.get(actor);
            entries.add(new Entry(a.getCreatedAt(), a.getAction(), "AUDIT", actor, kind, label,
                    a.getPayload() == null ? Map.of() : a.getPayload()));
        }
        // Tri stable : à date égale, la date du paiement précède l'entrée d'audit qui la raconte.
        entries.sort(Comparator.comparing(Entry::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return entries;
    }

    private static void milestone(List<Entry> entries, LocalDateTime at, String action) {
        if (at != null) {
            entries.add(new Entry(at, action, "PAYMENT", null, null, null, Map.of()));
        }
    }

    private static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Map<UUID, String> adminEmailsOf(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> emails = new HashMap<>();
        jdbc.query("SELECT CAST(id AS VARCHAR(36)) AS id, email FROM admin_users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", new ArrayList<>(ids)),
                rs -> { emails.put(UUID.fromString(rs.getString("id")), Objects.toString(rs.getString("email"), "")); });
        return emails;
    }
}
