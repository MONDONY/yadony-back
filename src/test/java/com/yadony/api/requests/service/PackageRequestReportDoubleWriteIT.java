package com.yadony.api.requests.service;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.dto.PackageRequestReportRequest;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.PackageRequestReportRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Double écriture réelle (contexte Spring, H2) : un signalement de demande atterrit aussi
 * dans la boîte générique, une seule fois même si le signalant insiste.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PackageRequestReportDoubleWriteIT {

    @Autowired PackageRequestReportService reportService;
    @Autowired PackageRequestRepository requestRepository;
    @Autowired PackageRequestReportRepository legacyRepository;
    @Autowired ReportRepository reportRepository;

    @Test
    void signalement_copieUniqueDansLaBoiteGenerique() {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(UUID.randomUUID());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(10));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5.00"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.OPEN);
        UUID requestId = requestRepository.saveAndFlush(e).getId();
        UUID reporter = UUID.randomUUID();

        reportService.report(reporter, requestId, new PackageRequestReportRequest("PROHIBITED", "armes"));
        reportService.report(reporter, requestId, new PackageRequestReportRequest("PROHIBITED", "armes"));

        assertThat(legacyRepository.existsByPackageRequestIdAndReporterId(requestId, reporter)).isTrue();
        List<ReportEntity> generic = reportRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType.PACKAGE_REQUEST, requestId);
        assertThat(generic).singleElement().satisfies(r -> {
            assertThat(r.getReporterId()).isEqualTo(reporter);
            assertThat(r.getReason()).isEqualTo(ReportReason.PROHIBITED_ITEM);
            assertThat(r.getDescription()).isEqualTo("armes");
        });
    }
}
