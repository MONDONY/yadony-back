package com.yadony.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService — tests unitaires")
class AuditServiceTest {

    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks private AuditService auditService;

    @Test
    @DisplayName("log() → enregistre correctement l'AuditLogEntity en base")
    void log_validData_savesEntity() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("key", "value", "count", 42);

        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.log("USER", entityId, "USER_CREATED", actorId, payload);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo("USER");
        assertThat(saved.getEntityId()).isEqualTo(entityId);
        assertThat(saved.getAction()).isEqualTo("USER_CREATED");
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getPayload()).containsEntry("key", "value");
        assertThat(saved.getPayload()).containsEntry("count", 42);
    }

    @Test
    @DisplayName("log() → fonctionne avec un payload vide")
    void log_emptyPayload_savesEntity() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.log("BID", entityId, "BID_ACCEPTED", actorId, Map.of());

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo("BID");
        assertThat(saved.getAction()).isEqualTo("BID_ACCEPTED");
        assertThat(saved.getPayload()).isEmpty();
    }

    @Test
    @DisplayName("log() → fonctionne avec entityId et actorId null")
    void log_nullIds_savesEntityWithNulls() {
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.log("SYSTEM", null, "STARTUP", null, Map.of("version", "1.0"));

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getEntityId()).isNull();
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getEntityType()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("log() → masque la PII du payload, garde IDs/montants/statuts")
    void log_redactsPii_keepsNonPii() {
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        // PII → doit être masquée
        payload.put("fullName", "Awa Diop");
        payload.put("recipientPhone", "+221701234567");
        payload.put("email", "awa@example.com");
        payload.put("disclaimerSignedIp", "196.1.2.3");
        payload.put("label", "12 rue de la Paix");
        payload.put("city", "Dakar");
        payload.put("latitude", 14.69);
        // Non-PII → doit rester
        payload.put("recipientId", "rid-123");   // contient "ip" mais pas PII
        payload.put("bidId", "bid-9");
        payload.put("amount", 42);
        payload.put("status", "ACCEPTED");
        // UID Firebase d'une session anonyme reclamee (GuestClaimService). C'est la seule
        // information qui relie l'entree d'audit aux deux sessions : si redact() la masquait
        // un jour, l'entree perdrait tout interet. Verrou explicite.
        payload.put("guestUid", "anon-uid-42");

        auditService.log("BID", UUID.randomUUID(), "BID_CREATED", UUID.randomUUID(), payload);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        Map<String, Object> saved = captor.getValue().getPayload();

        assertThat(saved).containsEntry("fullName", AuditService.REDACTED);
        assertThat(saved).containsEntry("recipientPhone", AuditService.REDACTED);
        assertThat(saved).containsEntry("email", AuditService.REDACTED);
        assertThat(saved).containsEntry("disclaimerSignedIp", AuditService.REDACTED);
        assertThat(saved).containsEntry("label", AuditService.REDACTED);
        assertThat(saved).containsEntry("city", AuditService.REDACTED);
        assertThat(saved).containsEntry("latitude", AuditService.REDACTED);
        // Non-PII intacts
        assertThat(saved).containsEntry("recipientId", "rid-123");
        assertThat(saved).containsEntry("bidId", "bid-9");
        assertThat(saved).containsEntry("amount", 42);
        assertThat(saved).containsEntry("status", "ACCEPTED");
        assertThat(saved).containsEntry("guestUid", "anon-uid-42");
    }

    private <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
