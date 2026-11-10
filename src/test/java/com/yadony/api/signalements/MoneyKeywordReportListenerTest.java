package com.yadony.api.signalements;

import com.yadony.api.matching.events.BidDescriptionSubmittedEvent;
import com.yadony.api.requests.event.PackageRequestDescriptionSubmittedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MoneyKeywordReportListener : signalement automatique d'une description qui évoque de l'argent")
class MoneyKeywordReportListenerTest {

    @Mock ReportService reportService;
    @InjectMocks MoneyKeywordReportListener listener;

    @Test
    void colisAvecDuCash_signaleLeBid() {
        UUID bidId = UUID.randomUUID();
        when(reportService.recordMoneyKeywordReport(ReportTargetType.BID, bidId, "cash"))
                .thenReturn(Optional.of(new ReportEntity()));

        listener.onBidDescription(new BidDescriptionSubmittedEvent(bidId, UUID.randomUUID(), "Vêtements et cash"));

        verify(reportService).recordMoneyKeywordReport(ReportTargetType.BID, bidId, "cash");
    }

    @Test
    void demandeAvecDesEspeces_signaleLaDemande() {
        UUID requestId = UUID.randomUUID();
        when(reportService.recordMoneyKeywordReport(ReportTargetType.PACKAGE_REQUEST, requestId, "especes"))
                .thenReturn(Optional.empty());

        listener.onPackageRequestDescription(
                new PackageRequestDescriptionSubmittedEvent(requestId, UUID.randomUUID(), "Des espèces pour ma mère"));

        verify(reportService).recordMoneyKeywordReport(ReportTargetType.PACKAGE_REQUEST, requestId, "especes");
    }

    @Test
    void descriptionSansMotSensible_neSignaleRien() {
        listener.onBidDescription(new BidDescriptionSubmittedEvent(UUID.randomUUID(), UUID.randomUUID(), "Vêtements"));
        listener.onPackageRequestDescription(
                new PackageRequestDescriptionSubmittedEvent(UUID.randomUUID(), UUID.randomUUID(), null));

        verify(reportService, never()).recordMoneyKeywordReport(any(), any(), anyString());
    }

    @Test
    void cibleSansIdentifiant_neSignaleRien() {
        listener.onBidDescription(new BidDescriptionSubmittedEvent(null, UUID.randomUUID(), "cash"));

        verify(reportService, never()).recordMoneyKeywordReport(any(), any(), anyString());
    }

    @Test
    void echecDuSignalement_nAnnulePasLEnvoi() {
        UUID bidId = UUID.randomUUID();
        when(reportService.recordMoneyKeywordReport(ReportTargetType.BID, bidId, "cash"))
                .thenThrow(new DataIntegrityViolationException("uq_reports_system_open_target"));

        assertThatCode(() -> listener.onBidDescription(
                new BidDescriptionSubmittedEvent(bidId, UUID.randomUUID(), "cash")))
                .doesNotThrowAnyException();
    }
}
