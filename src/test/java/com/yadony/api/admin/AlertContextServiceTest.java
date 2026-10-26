package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminAlertContextResponse;
import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertContextServiceTest {

    @Mock AdminAlertRepository alertRepo;
    @Mock ReportContextService reportContext;

    private AlertContextService service() {
        return new AlertContextService(alertRepo, reportContext);
    }

    private static AdminAlertEntity alert(UUID id, String type, String payload, LocalDateTime createdAt, boolean resolved) {
        AdminAlertEntity e = new AdminAlertEntity();
        ReflectionTestUtils.setField(e, "id", id);
        ReflectionTestUtils.setField(e, "createdAt", createdAt);
        e.setType(type);
        e.setSeverity("WARN");
        e.setPayload(payload);
        e.setResolved(resolved);
        return e;
    }

    @Test
    void build_listsTheOtherAlertsOnTheSamePaymentAndBidNewestFirst() {
        UUID id = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID bid = UUID.randomUUID();
        AdminAlertEntity self = alert(id, "ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + payment + "\",\"bidId\":\"" + bid + "\"}",
                LocalDateTime.parse("2026-10-20T10:00:00"), false);
        AdminAlertEntity older = alert(UUID.randomUUID(), "RECON_STRIPE_" + payment, "{}", LocalDateTime.parse("2026-10-10T10:00:00"), true);
        AdminAlertEntity newer = alert(UUID.randomUUID(), "RETURN_DEADLINE_EXPIRED", "{\"bidId\":\"" + bid + "\"}", LocalDateTime.parse("2026-10-18T10:00:00"), false);
        when(alertRepo.findReferencing(eq("%" + payment + "%"), eq(id), anyInt())).thenReturn(List.of(older));
        when(alertRepo.findReferencing(eq("%" + bid + "%"), eq(id), anyInt())).thenReturn(List.of(newer, older));
        List<HistoryEntry> history = List.of(new HistoryEntry("ADMIN_ALERT_RESOLVED", null, LocalDateTime.now(), "moussa@yadony.com", "ok"));
        when(reportContext.history("ADMIN_ALERT", id)).thenReturn(history);

        AdminAlertContextResponse ctx = service().build(self);

        assertThat(ctx.related()).extracting(AdminAlertContextResponse.RelatedAlert::id)
                .containsExactly(newer.getId(), older.getId());
        assertThat(ctx.related().get(1).resolved()).isTrue();
        assertThat(ctx.history()).isEqualTo(history);
    }

    @Test
    void build_withoutAnyReference_asksNothingAndReturnsNoRelatedAlert() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity self = alert(id, "PAWAPAY_BALANCE_LOW_XOF", "{\"bidId\":\"pas-un-uuid\"}", LocalDateTime.now(), false);
        when(reportContext.history("ADMIN_ALERT", id)).thenReturn(List.of());

        AdminAlertContextResponse ctx = service().build(self);

        assertThat(ctx.related()).isEmpty();
        verify(alertRepo, never()).findReferencing(any(), any(), anyInt());
    }

    @Test
    void build_samePaymentAndBidId_isQueriedOnce() {
        UUID id = UUID.randomUUID();
        UUID same = UUID.randomUUID();
        AdminAlertEntity self = alert(id, "X", "{\"paymentId\":\"" + same + "\",\"bidId\":\"" + same + "\"}", LocalDateTime.now(), false);
        when(alertRepo.findReferencing(eq("%" + same + "%"), eq(id), anyInt())).thenReturn(List.of());
        when(reportContext.history("ADMIN_ALERT", id)).thenReturn(List.of());

        service().build(self);

        verify(alertRepo).findReferencing(eq("%" + same + "%"), eq(id), anyInt());
    }

    @Test
    void build_alertWithoutDate_sortsLast() {
        UUID id = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        AdminAlertEntity self = alert(id, "ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + payment + "\"}", LocalDateTime.now(), false);
        AdminAlertEntity dated = alert(UUID.randomUUID(), "A", "{}", LocalDateTime.parse("2026-10-10T10:00:00"), false);
        AdminAlertEntity undated = alert(UUID.randomUUID(), "B", "{}", null, false);
        when(alertRepo.findReferencing(eq("%" + payment + "%"), eq(id), anyInt())).thenReturn(List.of(undated, dated));
        when(reportContext.history("ADMIN_ALERT", id)).thenReturn(List.of());

        assertThat(service().build(self).related()).extracting(AdminAlertContextResponse.RelatedAlert::id)
                .containsExactly(dated.getId(), undated.getId());
    }
}
