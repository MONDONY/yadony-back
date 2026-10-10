package com.yadony.api.signalements;

import com.yadony.api.common.PackageRequestMediaRetentionHold;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Un signalement ouvert sur la demande d'envoi retient ses photos. */
@Component
public class ReportPackageRequestMediaRetentionHold implements PackageRequestMediaRetentionHold {

    private final ReportRepository reportRepository;

    public ReportPackageRequestMediaRetentionHold(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @Override
    public boolean holds(UUID packageRequestId) {
        return packageRequestId != null && reportRepository.existsByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.PACKAGE_REQUEST, packageRequestId);
    }
}
