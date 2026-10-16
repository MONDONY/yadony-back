package com.yadony.api.maintenance;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pilotage du mode maintenance depuis le back-office. Chaque écriture est tracée dans
 * audit_log et publie {@link MaintenanceChangedEvent} : après le commit, le cache du
 * filtre est vidé et le document Firestore {@code config/maintenance}, écouté par
 * l'application, est réécrit.
 */
@Service
@Transactional
public class MaintenanceService {

    static final int MESSAGE_MAX_LENGTH = 300;
    static final int NOTE_MAX_LENGTH = 120;

    public record UpdateCommand(boolean enabled, String message, Instant estimatedEndAt) {}

    private final MaintenanceStateRepository states;
    private final MaintenanceExemptionRepository exemptions;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    public MaintenanceService(MaintenanceStateRepository states,
                              MaintenanceExemptionRepository exemptions,
                              AuditService auditService,
                              ApplicationEventPublisher events) {
        this.states = states;
        this.exemptions = exemptions;
        this.auditService = auditService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public MaintenanceStateEntity state() {
        return states.findById(MaintenanceStateEntity.SINGLETON_ID).orElseGet(MaintenanceService::blankState);
    }

    public MaintenanceStateEntity update(UpdateCommand command, UUID adminId) {
        String message = command.message() == null || command.message().isBlank() ? null : command.message().trim();
        if (message != null && message.length() > MESSAGE_MAX_LENGTH) {
            throw badRequest("maintenance-message-too-long",
                    "Le message ne doit pas dépasser " + MESSAGE_MAX_LENGTH + " caractères");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime estimatedEnd = command.estimatedEndAt() == null ? null
                : LocalDateTime.ofInstant(command.estimatedEndAt(), ZoneOffset.UTC);
        if (command.enabled() && estimatedEnd != null && !estimatedEnd.isAfter(now)) {
            throw badRequest("maintenance-end-in-past", "L'heure de fin estimée doit être dans le futur");
        }

        MaintenanceStateEntity state = state();
        boolean wasEnabled = state.isEnabled();
        state.setEnabled(command.enabled());
        state.setMessage(message);
        state.setEstimatedEndAt(command.enabled() ? estimatedEnd : null);
        if (command.enabled() && !wasEnabled) {
            state.setStartedAt(now);
        } else if (!command.enabled()) {
            state.setStartedAt(null);
        }
        state.setUpdatedBy(adminId);
        state.setUpdatedAt(now);
        MaintenanceStateEntity saved = states.save(state);

        String action = command.enabled() == wasEnabled ? "MAINTENANCE_UPDATED"
                : command.enabled() ? "MAINTENANCE_ENABLED" : "MAINTENANCE_DISABLED";
        Map<String, Object> payload = new HashMap<>();
        payload.put("enabled", command.enabled());
        payload.put("message", message);
        payload.put("estimatedEndAt", estimatedEnd == null ? null : estimatedEnd.toString());
        auditService.log("maintenance", null, action, adminId, payload);
        events.publishEvent(new MaintenanceChangedEvent());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceExemptionEntity> exemptions() {
        return exemptions.findAllByOrderByCreatedAtDesc();
    }

    /** Idempotent : un compte déjà exempté garde son exemption d'origine. */
    public MaintenanceExemptionEntity addExemption(UUID userId, String note, UUID adminId) {
        Objects.requireNonNull(userId, "userId");
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > NOTE_MAX_LENGTH) {
            throw badRequest("maintenance-note-too-long",
                    "La note ne doit pas dépasser " + NOTE_MAX_LENGTH + " caractères");
        }
        return exemptions.findByUserId(userId).orElseGet(() -> {
            MaintenanceExemptionEntity exemption = new MaintenanceExemptionEntity();
            exemption.setUserId(userId);
            exemption.setNote(cleanNote);
            exemption.setCreatedBy(adminId);
            MaintenanceExemptionEntity saved = exemptions.save(exemption);
            auditService.log("maintenance_exemption", saved.getId(), "MAINTENANCE_EXEMPTION_ADDED", adminId,
                    Map.of("userId", userId.toString()));
            events.publishEvent(new MaintenanceChangedEvent());
            return saved;
        });
    }

    public void removeExemption(UUID userId, UUID adminId) {
        MaintenanceExemptionEntity exemption = exemptions.findByUserId(userId).orElseThrow(() ->
                new YadonyBusinessException(HttpStatus.NOT_FOUND, "maintenance-exemption-not-found",
                        "Not Found", "Ce compte n'est pas exempté"));
        exemption.softDelete();
        exemptions.save(exemption);
        auditService.log("maintenance_exemption", exemption.getId(), "MAINTENANCE_EXEMPTION_REMOVED", adminId,
                Map.of("userId", userId.toString()));
        events.publishEvent(new MaintenanceChangedEvent());
    }

    private static MaintenanceStateEntity blankState() {
        MaintenanceStateEntity state = new MaintenanceStateEntity();
        state.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return state;
    }

    private static YadonyBusinessException badRequest(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Unprocessable Entity", detail);
    }
}
