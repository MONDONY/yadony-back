package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeletionTraceServiceTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock AdminUserRepository adminUserRepository;

    private static AuditLogEntity entry(UUID entityId, UUID actor, Map<String, Object> payload, LocalDateTime at) {
        AuditLogEntity e = new AuditLogEntity();
        e.setEntityId(entityId);
        e.setActorId(actor);
        e.setPayload(payload);
        ReflectionTestUtils.setField(e, "createdAt", at);
        return e;
    }

    @Test
    void latest_keepsNewestTracePerEntity_andResolvesAdminEmail() {
        UUID ratingA = UUID.randomUUID();
        UUID ratingB = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID oldAdmin = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);
        when(auditLogRepository.findByEntityTypeAndActionAndEntityIdInOrderByCreatedAtDescIdDesc(
                any(), any(), any())).thenReturn(List.of(
                entry(ratingA, admin, Map.of("reason", "recent"), now),
                entry(ratingA, oldAdmin, Map.of("reason", "ancien"), now.minusDays(3)),
                entry(ratingB, null, null, now.minusDays(1))));
        AdminUserEntity adminUser = mock(AdminUserEntity.class);
        when(adminUser.getId()).thenReturn(admin);
        when(adminUser.getEmail()).thenReturn("admin@yadony.test");
        when(adminUserRepository.findAllById(any())).thenReturn(List.of(adminUser));

        var traces = new DeletionTraceService(auditLogRepository, adminUserRepository)
                .latest("RATING", "RATING_DELETED", List.of(ratingA, ratingB));

        assertThat(traces.get(ratingA).adminId()).isEqualTo(admin);
        assertThat(traces.get(ratingA).adminEmail()).isEqualTo("admin@yadony.test");
        assertThat(traces.get(ratingA).reason()).isEqualTo("recent");
        assertThat(traces.get(ratingA).at()).isEqualTo(now);
        // Trace historique sans acteur (bug corrige) : ni email ni motif, sans planter.
        assertThat(traces.get(ratingB).adminEmail()).isNull();
        assertThat(traces.get(ratingB).reason()).isNull();
    }

    @Test
    void latest_withoutIds_readsNothing() {
        var traces = new DeletionTraceService(auditLogRepository, adminUserRepository)
                .latest("RATING", "RATING_DELETED", List.of());

        assertThat(traces).isEmpty();
        verifyNoInteractions(auditLogRepository, adminUserRepository);
    }
}
