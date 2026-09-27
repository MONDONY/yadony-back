package com.yadony.api.signalements;

import com.yadony.api.requests.event.PackageRequestReportedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Double écriture des signalements de demande d'envoi : chaque nouveau signalement de
 * {@code package_request_reports} rejoint la boîte générique {@code reports} (cible
 * {@link ReportTargetType#PACKAGE_REQUEST}), que l'admin Signalements lit déjà.
 *
 * <p>{@code @EventListener} synchrone, volontairement : l'écriture a lieu dans la
 * transaction du signalement, les deux lignes existent ou aucune.
 */
@Component
public class PackageRequestReportedListener {

    private final ReportService reportService;

    public PackageRequestReportedListener(ReportService reportService) {
        this.reportService = reportService;
    }

    @EventListener
    public void onPackageRequestReported(PackageRequestReportedEvent event) {
        reportService.recordPackageRequestReport(
                event.packageRequestId(), event.reporterId(), event.reason(), event.details());
    }
}
