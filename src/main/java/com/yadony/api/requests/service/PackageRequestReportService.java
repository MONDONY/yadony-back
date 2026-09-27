package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.requests.dto.PackageRequestReportRequest;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestReportEntity;
import com.yadony.api.requests.event.PackageRequestReportedEvent;
import com.yadony.api.requests.repository.PackageRequestReportRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/** Signalement (modération) d'une demande d'envoi. */
@Service
public class PackageRequestReportService {

    private final PackageRequestRepository requestRepository;
    private final PackageRequestReportRepository reportRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public PackageRequestReportService(PackageRequestRepository requestRepository,
                                       PackageRequestReportRepository reportRepository,
                                       AuditService auditService,
                                       ApplicationEventPublisher eventPublisher) {
        this.requestRepository = requestRepository;
        this.reportRepository = reportRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Signale une demande. Idempotent par couple (demande, reporter) — re-signaler ne crée
     * pas de doublon. L'auto-signalement (signaler sa propre demande) est interdit (422).
     *
     * <p>Un signalement nouveau publie {@link PackageRequestReportedEvent} : la boîte
     * générique des signalements (admin) en reçoit une copie dans la même transaction.
     * Un re-signalement ne publie rien, la copie reste donc unique elle aussi.
     */
    @Transactional
    public void report(UUID reporterId, UUID requestId, PackageRequestReportRequest req) {
        PackageRequestEntity entity = requestRepository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (entity.getSenderId().equals(reporterId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/cannot-report-own");
        }
        if (reportRepository.existsByPackageRequestIdAndReporterId(requestId, reporterId)) {
            return; // déjà signalé — idempotent
        }
        reportRepository.save(new PackageRequestReportEntity(requestId, reporterId, req.reason(), req.details()));
        auditService.log("PACKAGE_REQUEST", requestId, "REPORTED", reporterId,
            Map.of("reason", req.reason()));
        eventPublisher.publishEvent(new PackageRequestReportedEvent(
            requestId, reporterId, req.reason(), req.details()));
    }
}
