package com.yadony.api.signalements;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService.recordMoneyKeywordReport : signalement système sans signalant")
class ReportServiceMoneyKeywordTest {

    @Mock ReportRepository reportRepository;
    @Mock ReportPhotoRepository photoRepository;
    @Mock UserRepository userRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock RatingRepository ratingRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock StorageService storageService;
    @Mock AuditService auditService;
    @Mock com.yadony.api.common.stripe.AdminAlertService adminAlertService;
    @Mock ConversationRepository conversationRepository;

    private ReportService service() {
        return new ReportService(reportRepository, photoRepository, userRepository,
                announcementRepository, bidRepository, ratingRepository, packageRequestRepository,
                conversationRepository, storageService, auditService, adminAlertService);
    }

    @Test
    void creeUnSignalementSystemeOuvertEtLAudite() {
        UUID bidId = UUID.randomUUID();
        when(reportRepository.existsByTargetTypeAndTargetIdAndSourceAndStatus(
                ReportTargetType.BID, bidId, ReportSource.SYSTEM, ReportStatus.OPEN)).thenReturn(false);
        when(reportRepository.save(any(ReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<ReportEntity> created = service().recordMoneyKeywordReport(ReportTargetType.BID, bidId, "cash");

        assertThat(created).isPresent();
        ReportEntity report = created.get();
        assertThat(report.getTargetType()).isEqualTo(ReportTargetType.BID);
        assertThat(report.getTargetId()).isEqualTo(bidId);
        assertThat(report.getReporterId()).isNull();
        assertThat(report.getReason()).isEqualTo(ReportReason.PROHIBITED_ITEM);
        assertThat(report.getSource()).isEqualTo(ReportSource.SYSTEM);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.getDescription()).contains("« cash »").contains("CGU article 4");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("REPORT"), any(), eq("REPORT_CREATED"), isNull(), audit.capture());
        assertThat(audit.getValue())
                .containsEntry("source", "SYSTEM")
                .containsEntry("term", "cash")
                .containsEntry("reason", "PROHIBITED_ITEM")
                .containsEntry("targetId", bidId.toString());
    }

    @Test
    void signalementSystemeDejaOuvert_rienDeNeuf() {
        UUID requestId = UUID.randomUUID();
        when(reportRepository.existsByTargetTypeAndTargetIdAndSourceAndStatus(
                ReportTargetType.PACKAGE_REQUEST, requestId, ReportSource.SYSTEM, ReportStatus.OPEN)).thenReturn(true);

        Optional<ReportEntity> created = service().recordMoneyKeywordReport(
                ReportTargetType.PACKAGE_REQUEST, requestId, "especes");

        assertThat(created).isEmpty();
        verify(reportRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void unSignalementOrdinaireResteDeSourceUtilisateur() {
        assertThat(new ReportEntity().getSource()).isEqualTo(ReportSource.USER);
    }
}
