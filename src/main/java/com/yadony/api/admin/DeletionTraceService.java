package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Qui a supprimé un élément, quand, et pourquoi : lu dans {@code audit_log} plutôt que
 * dupliqué dans une colonne. L'audit est immuable et écrit à chaque suppression, il fait
 * déjà foi ; une colonne {@code deleted_by} serait une seconde vérité à tenir cohérente.
 */
@Service
@Transactional(readOnly = true)
public class DeletionTraceService {

    /**
     * @param adminId    auteur de la suppression ; {@code null} pour une trace historique sans acteur
     * @param adminEmail email du compte admin, {@code null} s'il est inconnu ou supprimé
     * @param reason     valeur de la clé {@code reason} du payload, telle qu'auditée
     */
    public record DeletionTrace(UUID adminId, String adminEmail, String reason, LocalDateTime at) {}

    private final AuditLogRepository auditLogRepository;
    private final AdminUserRepository adminUserRepository;

    public DeletionTraceService(AuditLogRepository auditLogRepository, AdminUserRepository adminUserRepository) {
        this.auditLogRepository = auditLogRepository;
        this.adminUserRepository = adminUserRepository;
    }

    /** Dernière trace {@code action} de chaque entité (une suppression peut suivre une restauration). */
    public Map<UUID, DeletionTrace> latest(String entityType, String action, Collection<UUID> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AuditLogEntity> newest = new LinkedHashMap<>();
        for (AuditLogEntity entry : auditLogRepository
                .findByEntityTypeAndActionAndEntityIdInOrderByCreatedAtDescIdDesc(entityType, action, entityIds)) {
            newest.putIfAbsent(entry.getEntityId(), entry);
        }
        Set<UUID> adminIds = newest.values().stream()
                .map(AuditLogEntity::getActorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> emails = new HashMap<>();
        if (!adminIds.isEmpty()) {
            for (AdminUserEntity admin : adminUserRepository.findAllById(adminIds)) {
                emails.put(admin.getId(), admin.getEmail());
            }
        }
        Map<UUID, DeletionTrace> traces = new HashMap<>();
        newest.forEach((entityId, entry) -> {
            Object reason = entry.getPayload() != null ? entry.getPayload().get("reason") : null;
            traces.put(entityId, new DeletionTrace(
                    entry.getActorId(),
                    entry.getActorId() != null ? emails.get(entry.getActorId()) : null,
                    reason != null ? reason.toString() : null,
                    entry.getCreatedAt()));
        });
        return traces;
    }
}
