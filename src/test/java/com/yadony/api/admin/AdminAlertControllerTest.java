package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminAlertResponse;
import com.yadony.api.admin.dto.ResolveAlertRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAlertControllerTest {

    @Mock AdminAlertRepository alertRepo;
    @Mock com.yadony.api.common.AuditService auditService;
    @Mock com.yadony.api.payments.integrity.MoneyIntegrityMonitor moneyIntegrityMonitor;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static org.springframework.security.core.Authentication adminAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.yadony.api.admin.account.AdminPrincipal(ADMIN_ID, "admin@yadony.test",
                        com.yadony.api.admin.account.AdminRole.SUPPORT, false, "uid-admin"),
                null, List.of());
    }

    private AdminAlertController controller() {
        return new AdminAlertController(alertRepo, auditService, moneyIntegrityMonitor);
    }

    @Test
    void list_returnsPage() {
        AdminAlertEntity entity = new AdminAlertEntity();
        Page<AdminAlertEntity> page = new PageImpl<>(List.of(entity));
        when(alertRepo.findFiltered(isNull(), isNull(), eq(false), any())).thenReturn(page);

        ResponseEntity<?> resp = controller().list(null, null, false, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resolve_marksResolvedAndReturns200() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        when(alertRepo.save(entity)).thenReturn(entity);

        ResponseEntity<AdminAlertResponse> resp =
            controller().resolve(id, new ResolveAlertRequest("note de résolution"), adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.isResolved()).isTrue();
        verify(alertRepo).save(entity);
    }

    @Test
    void resolve_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(alertRepo.findById(id)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            com.yadony.api.common.YadonyBusinessException.class,
            () -> controller().resolve(id, new ResolveAlertRequest("note"), adminAuth())
        );
        verifyNoInteractions(auditService);
    }

    @Test
    void resolve_writesAuditEntryWithAdminAsActorAndNote() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        entity.setType("PAYOUT_FAILED");
        entity.setSeverity("CRITICAL");
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        when(alertRepo.save(entity)).thenReturn(entity);

        controller().resolve(id, new ResolveAlertRequest("rejoué à la main"), adminAuth());

        verify(auditService).log(eq("ADMIN_ALERT"), eq(id), eq("ADMIN_ALERT_RESOLVED"), eq(ADMIN_ID),
                eq(java.util.Map.of("alertType", "PAYOUT_FAILED",
                        "severity", "CRITICAL",
                        "note", "rejoué à la main")));
    }

    @Test
    void resolve_withoutNote_auditsEmptyNote() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        entity.setType("X");
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        when(alertRepo.save(entity)).thenReturn(entity);

        controller().resolve(id, null, adminAuth());

        verify(auditService).log(eq("ADMIN_ALERT"), eq(id), eq("ADMIN_ALERT_RESOLVED"), eq(ADMIN_ID),
                eq(java.util.Map.of("alertType", "X", "severity", "INFO", "note", "")));
    }

    @Test
    void list_exposesTheReadableDetail() {
        AdminAlertEntity entity = new AdminAlertEntity();
        entity.setType("PAWAPAY_BALANCE_LOW_XOF");
        entity.setDetail("Solde pawaPay XOF sous le seuil : 1000");
        when(alertRepo.findFiltered(isNull(), isNull(), isNull(), any())).thenReturn(new PageImpl<>(List.of(entity)));

        ResponseEntity<Page<AdminAlertResponse>> resp = controller().list(null, null, null, 0, 20);

        assertThat(resp.getBody().getContent().get(0).detail()).isEqualTo("Solde pawaPay XOF sous le seuil : 1000");
    }

    @Test
    void violations_reRunsTheMoneyInvariantOfTheAlert() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        entity.setType("MONEY_INVARIANT_INV-05");
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        var inspection = new com.yadony.api.payments.integrity.MoneyIntegrityMonitor.Inspection(
                "INV-05", "Argent versé au voyageur seulement si le colis est livré", "CRITIQUE", 7,
                List.of(java.util.Map.of("payment_id", "p-1")));
        when(moneyIntegrityMonitor.inspect("INV-05", 20)).thenReturn(Optional.of(inspection));

        var resp = controller().violations(id, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().invariant()).isEqualTo("INV-05");
        assertThat(resp.getBody().total()).isEqualTo(7);
        assertThat(resp.getBody().rows()).containsExactly(java.util.Map.of("payment_id", "p-1"));
    }

    @Test
    void violations_onAnotherAlertType_is422() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        entity.setType("ESCROW_J48_TIMEOUT");
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class, () -> controller().violations(id, 50));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(moneyIntegrityMonitor);
    }

    @Test
    void violations_unknownInvariant_is404() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        entity.setType("MONEY_INVARIANT_INV-99");
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        when(moneyIntegrityMonitor.inspect("INV-99", 50)).thenReturn(Optional.empty());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class, () -> controller().violations(id, 50));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void violations_alertNotFound_is404() {
        UUID id = UUID.randomUUID();
        when(alertRepo.findById(id)).thenReturn(Optional.empty());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class, () -> controller().violations(id, 50));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
