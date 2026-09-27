package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.requests.dto.PackageRequestReportRequest;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestReportEntity;
import com.yadony.api.requests.event.PackageRequestReportedEvent;
import com.yadony.api.requests.repository.PackageRequestReportRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageRequestReportServiceTest {

    @Mock private PackageRequestRepository requestRepository;
    @Mock private PackageRequestReportRepository reportRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private PackageRequestReportService service;

    private static final UUID REQ = UUID.randomUUID();
    private static final UUID REPORTER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private PackageRequestEntity entityOwnedBy(UUID owner) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(owner);
        return e;
    }

    @Test
    void report_persistsAndAudits() {
        when(requestRepository.findById(REQ)).thenReturn(Optional.of(entityOwnedBy(OWNER)));
        when(reportRepository.existsByPackageRequestIdAndReporterId(REQ, REPORTER)).thenReturn(false);

        service.report(REPORTER, REQ, new PackageRequestReportRequest("SCAM", "annonce frauduleuse"));

        verify(reportRepository).save(any(PackageRequestReportEntity.class));
        verify(auditService).log(eq("PACKAGE_REQUEST"), eq(REQ), eq("REPORTED"), eq(REPORTER), anyMap());
        // Double écriture : la boîte générique des signalements reçoit sa copie.
        verify(eventPublisher).publishEvent(
            new PackageRequestReportedEvent(REQ, REPORTER, "SCAM", "annonce frauduleuse"));
    }

    @Test
    void report_notFound_throws404() {
        when(requestRepository.findById(REQ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report(REPORTER, REQ, new PackageRequestReportRequest("SCAM", null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not-found");
    }

    @Test
    void report_own_throws422() {
        when(requestRepository.findById(REQ)).thenReturn(Optional.of(entityOwnedBy(REPORTER)));

        assertThatThrownBy(() -> service.report(REPORTER, REQ, new PackageRequestReportRequest("SCAM", null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("cannot-report-own");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void report_duplicate_idempotentNoSave() {
        when(requestRepository.findById(REQ)).thenReturn(Optional.of(entityOwnedBy(OWNER)));
        when(reportRepository.existsByPackageRequestIdAndReporterId(REQ, REPORTER)).thenReturn(true);

        service.report(REPORTER, REQ, new PackageRequestReportRequest("SCAM", null));

        verify(reportRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
        // Idempotent jusque dans la boîte générique : aucune seconde copie.
        verify(eventPublisher, never()).publishEvent(any());
    }
}
