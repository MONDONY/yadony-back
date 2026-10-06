package com.yadony.api.admin;

import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.payments.PaymentEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminPaymentTimelineTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AdminPaymentInsights insights = mock(AdminPaymentInsights.class);
    private final AdminPaymentTimeline timeline = new AdminPaymentTimeline(auditLogRepository, jdbc, insights);

    private static AuditLogEntity audit(String action, UUID actor, LocalDateTime at) {
        AuditLogEntity a = new AuditLogEntity();
        ReflectionTestUtils.setField(a, "action", action);
        ReflectionTestUtils.setField(a, "actorId", actor);
        ReflectionTestUtils.setField(a, "createdAt", at);
        ReflectionTestUtils.setField(a, "payload", Map.of("reason", "test"));
        return a;
    }

    @Test
    void datesDuPaiementEtJournalTriesAvecAuteursResolus() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LocalDateTime created = LocalDateTime.of(2026, 10, 1, 10, 0);
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", paymentId);
        ReflectionTestUtils.setField(p, "createdAt", created);
        p.setCapturedAt(Instant.parse("2026-10-01T10:05:00Z"));
        p.setEscrowReleasedAt(LocalDateTime.of(2026, 10, 3, 9, 0));
        when(auditLogRepository.findTop200ByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("PAYMENT", paymentId))
                .thenReturn(List.of(
                        audit("PAYMENT_ESCROW_CREATED", userId, created),
                        audit("ESCROW_FORCE_RELEASED", adminId, LocalDateTime.of(2026, 10, 3, 9, 0)),
                        audit("PAYMENT_CAPTURED_ON_PLATFORM", null, LocalDateTime.of(2026, 10, 1, 10, 5))));
        doAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("id")).thenReturn(adminId.toString());
            when(rs.getString("email")).thenReturn("ops@yadony.com");
            ((RowCallbackHandler) inv.getArgument(2)).processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        when(insights.namesOf(argThat(ids -> ids.size() == 1 && ids.contains(userId))))
                .thenReturn(Map.of(userId, "Awa Diallo (@awa)"));

        List<AdminPaymentTimeline.Entry> entries = timeline.of(p);

        assertThat(entries).extracting(AdminPaymentTimeline.Entry::action).containsExactly(
                "PAYMENT_CREATED", "PAYMENT_ESCROW_CREATED",
                "PAYMENT_CAPTURED", "PAYMENT_CAPTURED_ON_PLATFORM",
                "ESCROW_RELEASED", "ESCROW_FORCE_RELEASED");
        AdminPaymentTimeline.Entry forced = entries.get(5);
        assertThat(forced.actorKind()).isEqualTo("ADMIN");
        assertThat(forced.actorLabel()).isEqualTo("ops@yadony.com");
        assertThat(forced.payload()).containsEntry("reason", "test");
        assertThat(entries.get(1).actorKind()).isEqualTo("USER");
        assertThat(entries.get(1).actorLabel()).isEqualTo("Awa Diallo (@awa)");
        assertThat(entries.get(3).actorKind()).isNull();
        assertThat(entries.get(0).source()).isEqualTo("PAYMENT");
    }

    @Test
    void sansJournalNiActeur_pasDeRequeteSurLesAdmins() {
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        when(auditLogRepository.findTop200ByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(eq("PAYMENT"), any()))
                .thenReturn(List.of());

        assertThat(timeline.of(p)).isEmpty();
    }
}
