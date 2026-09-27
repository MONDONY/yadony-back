package com.yadony.api.signalements;

import com.yadony.api.requests.event.PackageRequestReportedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PackageRequestReportedListenerTest {

    @Test
    void recopieLeSignalementDansLaBoiteGenerique() {
        ReportService reportService = mock(ReportService.class);
        UUID request = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();

        new PackageRequestReportedListener(reportService)
                .onPackageRequestReported(new PackageRequestReportedEvent(request, reporter, "SCAM", "d"));

        verify(reportService).recordPackageRequestReport(request, reporter, "SCAM", "d");
    }
}
