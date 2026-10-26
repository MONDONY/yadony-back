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
    @Mock AlertContextService contextService;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static org.springframework.security.core.Authentication adminAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.yadony.api.admin.account.AdminPrincipal(ADMIN_ID, "admin@yadony.test",
                        com.yadony.api.admin.account.AdminRole.SUPPORT, false, "uid-admin"),
                null, List.of());
    }

    private AdminAlertController controller() {
        return new AdminAlertController(alertRepo, auditService, moneyIntegrityMonitor, contextService);
    }

    @Test
    void list_returnsPage() {
        AdminAlertEntity entity = new AdminAlertEntity();
        Page<AdminAlertEntity> page = new PageImpl<>(List.of(entity));
        when(alertRepo.findFiltered(isNull(), isNull(), eq(false), isNull(), any(), any())).thenReturn(page);

        ResponseEntity<?> resp = controller().list(null, null, false, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void get_returnsTheAlert() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        entity.setType("ESCROW_J48_TIMEOUT");
        entity.setSeverity("WARN");
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));

        ResponseEntity<AdminAlertResponse> resp = controller().get(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().type()).isEqualTo("ESCROW_J48_TIMEOUT");
        assertThat(resp.getBody().severity()).isEqualTo("WARN");
    }

    @Test
    void get_notFound_is404AlertNotFound() {
        UUID id = UUID.randomUUID();
        when(alertRepo.findById(id)).thenReturn(Optional.empty());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class, () -> controller().get(id));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("alert-not-found");
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
        when(alertRepo.findFiltered(isNull(), isNull(), isNull(), isNull(), any(), any())).thenReturn(new PageImpl<>(List.of(entity)));

        ResponseEntity<Page<AdminAlertResponse>> resp = controller().list(null, null, null, null, 0, 20);

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

    @Test
    void list_passesTheSnoozedFilterToTheRepository() {
        when(alertRepo.findFiltered(isNull(), isNull(), eq(false), eq(false), any(), any())).thenReturn(Page.empty());

        controller().list(null, null, false, false, 0, 100);

        verify(alertRepo).findFiltered(isNull(), isNull(), eq(false), eq(false), any(java.time.OffsetDateTime.class), any());
    }

    @Test
    void list_exposesTheSnoozeDate() {
        AdminAlertEntity entity = new AdminAlertEntity();
        java.time.OffsetDateTime until = java.time.OffsetDateTime.now().plusHours(24);
        entity.setSnoozedUntil(until);
        when(alertRepo.findFiltered(isNull(), isNull(), isNull(), isNull(), any(), any())).thenReturn(new PageImpl<>(List.of(entity)));

        var resp = controller().list(null, null, null, null, 0, 20);

        assertThat(resp.getBody().getContent().get(0).snoozedUntil()).isEqualTo(until);
    }

    // ── context ─────────────────────────────────────────────────────────────

    @Test
    void context_delegatesToTheContextService() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        var context = new com.yadony.api.admin.dto.AdminAlertContextResponse(List.of(), List.of());
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        when(contextService.build(entity)).thenReturn(context);

        var resp = controller().context(id);

        assertThat(resp.getBody()).isSameAs(context);
    }

    @Test
    void context_unknownAlert_is404() {
        UUID id = UUID.randomUUID();
        when(alertRepo.findById(id)).thenReturn(Optional.empty());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class, () -> controller().context(id));

        assertThat(ex.getErrorCode()).isEqualTo("alert-not-found");
        verifyNoInteractions(contextService);
    }

    // ── resolve-batch ───────────────────────────────────────────────────────

    private static AdminAlertEntity alert(String type, boolean resolved) {
        AdminAlertEntity e = new AdminAlertEntity();
        e.setType(type);
        e.setSeverity("WARN");
        e.setResolved(resolved);
        return e;
    }

    private static com.yadony.api.admin.dto.AlertBatchDtos.ResolveBatchRequest batch(List<UUID> ids, String note) {
        return new com.yadony.api.admin.dto.AlertBatchDtos.ResolveBatchRequest(ids, note);
    }

    @Test
    void resolveBatch_resolvesEachAlertAndAuditsWithTheBatchFlag() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AdminAlertEntity ea = alert("ESCROW_J48_TIMEOUT", false);
        AdminAlertEntity eb = alert("ESCROW_J48_TIMEOUT", false);
        when(alertRepo.findById(a)).thenReturn(Optional.of(ea));
        when(alertRepo.findById(b)).thenReturn(Optional.of(eb));

        var resp = controller().resolveBatch(batch(List.of(a, b, a), "  Versement forcé "), adminAuth());

        assertThat(resp.getBody().resolved()).containsExactly(a, b);
        assertThat(resp.getBody().alreadyResolved()).isEmpty();
        assertThat(ea.isResolved()).isTrue();
        assertThat(ea.getResolvedAt()).isNotNull();
        verify(alertRepo, times(2)).save(any(AdminAlertEntity.class));
        verify(auditService).log(eq("ADMIN_ALERT"), eq(a), eq("ADMIN_ALERT_RESOLVED"), eq(ADMIN_ID),
                eq(java.util.Map.of("alertType", "ESCROW_J48_TIMEOUT", "severity", "WARN",
                        "note", "Versement forcé", "batch", "true")));
    }

    @Test
    void resolveBatch_reportsAlreadyResolvedAndUnknownAlertsWithoutFailing() {
        UUID done = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(alertRepo.findById(done)).thenReturn(Optional.of(alert("X", true)));
        when(alertRepo.findById(gone)).thenReturn(Optional.empty());

        var resp = controller().resolveBatch(batch(List.of(done, gone), "Déjà traité"), adminAuth());

        assertThat(resp.getBody().resolved()).isEmpty();
        assertThat(resp.getBody().alreadyResolved()).containsExactly(done);
        assertThat(resp.getBody().notFound()).containsExactly(gone);
        verify(alertRepo, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void resolveBatch_refusesAnInvalidNoteOrAnInvalidSelection() {
        UUID id = UUID.randomUUID();
        for (String note : new String[] {null, "  ", "ab", "x".repeat(501)}) {
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                    com.yadony.api.common.YadonyBusinessException.class,
                    () -> controller().resolveBatch(batch(List.of(id), note), adminAuth()));
            assertThat(ex.getErrorCode()).isEqualTo("alert-note-invalid");
        }
        for (List<UUID> ids : java.util.Arrays.asList(null, List.<UUID>of(),
                java.util.stream.Stream.generate(UUID::randomUUID).limit(101).toList())) {
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                    com.yadony.api.common.YadonyBusinessException.class,
                    () -> controller().resolveBatch(batch(ids, "Déjà traité"), adminAuth()));
            assertThat(ex.getErrorCode()).isEqualTo("alert-batch-invalid");
        }
        var nullBody = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller().resolveBatch(null, adminAuth()));
        assertThat(nullBody.getErrorCode()).isEqualTo("alert-note-invalid");
        verifyNoInteractions(auditService);
    }

    // ── reopen ──────────────────────────────────────────────────────────────

    @Test
    void reopen_putsTheAlertBackInTheQueueAndAudits() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = alert("PAYOUT_FAILED", true);
        entity.setResolvedAt(java.time.OffsetDateTime.now());
        entity.setSnoozedUntil(java.time.OffsetDateTime.now().plusHours(5));
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        when(alertRepo.findByTypeAndResolved("PAYOUT_FAILED", false)).thenReturn(List.of());

        var resp = controller().reopen(id, new com.yadony.api.admin.dto.AlertBatchDtos.ReopenAlertRequest("Revenu ce matin"), adminAuth());

        assertThat(resp.getBody().resolved()).isFalse();
        assertThat(entity.isResolved()).isFalse();
        assertThat(entity.getResolvedAt()).isNull();
        assertThat(entity.getSnoozedUntil()).isNull();
        verify(auditService).log(eq("ADMIN_ALERT"), eq(id), eq("ADMIN_ALERT_REOPENED"), eq(ADMIN_ID),
                eq(java.util.Map.of("alertType", "PAYOUT_FAILED", "severity", "WARN", "note", "Revenu ce matin")));
    }

    @Test
    void reopen_openAlert_is409NotResolved() {
        UUID id = UUID.randomUUID();
        when(alertRepo.findById(id)).thenReturn(Optional.of(alert("X", false)));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller().reopen(id, new com.yadony.api.admin.dto.AlertBatchDtos.ReopenAlertRequest("Revenu"), adminAuth()));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("alert-not-resolved");
    }

    @Test
    void reopen_whenTheSameTypeIsAlreadyOpen_is409() {
        UUID id = UUID.randomUUID();
        when(alertRepo.findById(id)).thenReturn(Optional.of(alert("X", true)));
        when(alertRepo.findByTypeAndResolved("X", false)).thenReturn(List.of(alert("X", false)));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller().reopen(id, new com.yadony.api.admin.dto.AlertBatchDtos.ReopenAlertRequest("Revenu"), adminAuth()));

        assertThat(ex.getErrorCode()).isEqualTo("alert-already-open-for-type");
        verify(alertRepo, never()).save(any());
    }

    @Test
    void reopen_validatesTheNoteAndTheAlert() {
        UUID id = UUID.randomUUID();
        var noNote = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller().reopen(id, null, adminAuth()));
        assertThat(noNote.getErrorCode()).isEqualTo("alert-note-invalid");
        when(alertRepo.findById(id)).thenReturn(Optional.empty());
        var missing = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller().reopen(id, new com.yadony.api.admin.dto.AlertBatchDtos.ReopenAlertRequest("Revenu"), adminAuth()));
        assertThat(missing.getErrorCode()).isEqualTo("alert-not-found");
    }

    // ── snooze ──────────────────────────────────────────────────────────────

    private static com.yadony.api.admin.dto.AlertBatchDtos.SnoozeAlertRequest snooze(Integer hours, String note) {
        return new com.yadony.api.admin.dto.AlertBatchDtos.SnoozeAlertRequest(hours, note);
    }

    @Test
    void snooze_hidesTheAlertUntilTheDeadlineAndAudits() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = alert("ESCROW_J48_TIMEOUT", false);
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        java.time.OffsetDateTime before = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);

        var resp = controller().snooze(id, snooze(24, "Colis encore en route"), adminAuth());

        assertThat(entity.getSnoozedUntil()).isBetween(before.plusHours(24), before.plusHours(24).plusMinutes(1));
        assertThat(resp.getBody().snoozedUntil()).isEqualTo(entity.getSnoozedUntil());
        verify(auditService).log(eq("ADMIN_ALERT"), eq(id), eq("ADMIN_ALERT_SNOOZED"), eq(ADMIN_ID),
                eq(java.util.Map.of("alertType", "ESCROW_J48_TIMEOUT", "severity", "WARN",
                        "note", "Colis encore en route", "hours", "24")));
    }

    @Test
    void snooze_refusesBadHoursNoteAndResolvedAlerts() {
        UUID id = UUID.randomUUID();
        for (Integer hours : new Integer[] {null, 0, -1, 169}) {
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                    com.yadony.api.common.YadonyBusinessException.class,
                    () -> controller().snooze(id, snooze(hours, "Colis en route"), adminAuth()));
            assertThat(ex.getErrorCode()).isEqualTo("alert-snooze-invalid");
        }
        var noBody = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller().snooze(id, null, adminAuth()));
        assertThat(noBody.getErrorCode()).isEqualTo("alert-note-invalid");
        when(alertRepo.findById(id)).thenReturn(Optional.of(alert("X", true)));
        var resolved = org.junit.jupiter.api.Assertions.assertThrows(
                com.yadony.api.common.YadonyBusinessException.class,
                () -> controller().snooze(id, snooze(24, "Colis en route"), adminAuth()));
        assertThat(resolved.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resolved.getErrorCode()).isEqualTo("alert-already-resolved");
        verify(alertRepo, never()).save(any());
    }

    @Test
    void unsnooze_putsTheAlertBackInTheQueue() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = alert("X", false);
        entity.setSnoozedUntil(java.time.OffsetDateTime.now().plusHours(10));
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));

        var resp = controller().unsnooze(id, adminAuth());

        assertThat(entity.getSnoozedUntil()).isNull();
        assertThat(resp.getBody().snoozedUntil()).isNull();
        verify(auditService).log(eq("ADMIN_ALERT"), eq(id), eq("ADMIN_ALERT_UNSNOOZED"), eq(ADMIN_ID),
                eq(java.util.Map.of("alertType", "X", "severity", "WARN", "note", "")));
    }
}
