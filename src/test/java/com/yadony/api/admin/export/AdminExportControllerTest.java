package com.yadony.api.admin.export;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminExportControllerTest {

    @Mock AdminExportService exportService;
    @Mock AuditService auditService;

    private static final java.util.UUID ADMIN_ID = java.util.UUID.randomUUID();

    private static org.springframework.security.core.Authentication adminAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.yadony.api.admin.account.AdminPrincipal(ADMIN_ID, "admin@yadony.test",
                        com.yadony.api.admin.account.AdminRole.ADMIN, false, "uid-admin"),
                null, java.util.List.of());
    }

    private AdminExportController controller() {
        return new AdminExportController(exportService, auditService);
    }

    @Test
    void download_transactions_returnsCsvAttachment() {
        when(exportService.exportTransactions(any(), any()))
                .thenReturn("header\n".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<byte[]> resp = controller().download("transactions",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("transactions_2026-01-01_2026-01-31.csv");
        verify(auditService).log(eq("EXPORT"), eq(null), eq("EXPORT_RUN"), eq(ADMIN_ID), any());
    }

    @Test
    void download_eachValidType_routesToRightService() {
        when(exportService.exportUsers(any(), any())).thenReturn(new byte[0]);
        when(exportService.exportDisputes(any(), any())).thenReturn(new byte[0]);
        when(exportService.exportPayouts(any(), any())).thenReturn(new byte[0]);

        controller().download("users", null, null, adminAuth());
        controller().download("disputes", null, null, adminAuth());
        controller().download("payouts", null, null, adminAuth());

        verify(exportService).exportUsers(null, null);
        verify(exportService).exportDisputes(null, null);
        verify(exportService).exportPayouts(null, null);
    }

    @Test
    void download_invalidType_throws400_andDoesNotAudit() {
        assertThatThrownBy(() -> controller().download("secrets", null, null, adminAuth()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void download_withoutDates_filenameHasNoRange() {
        when(exportService.exportUsers(any(), any())).thenReturn(new byte[0]);

        ResponseEntity<byte[]> resp = controller().download("users", null, null, adminAuth());

        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("users.csv");
    }

    @Test
    void download_withoutAdminPrincipal_throws403_beforeExporting() {
        org.springframework.security.core.Authentication notAdmin =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "firebase-uid", null, java.util.List.of());

        assertThatThrownBy(() -> controller().download("users", null, null, notAdmin))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(exportService, never()).exportUsers(any(), any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
