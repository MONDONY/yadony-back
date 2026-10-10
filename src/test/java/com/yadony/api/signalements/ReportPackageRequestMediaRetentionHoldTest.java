package com.yadony.api.signalements;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportPackageRequestMediaRetentionHoldTest {

    ReportRepository repository = mock(ReportRepository.class);
    ReportPackageRequestMediaRetentionHold hold = new ReportPackageRequestMediaRetentionHold(repository);
    UUID requestId = UUID.randomUUID();

    @Test
    void openReportOnTheRequest_holds() {
        when(repository.existsByStatusAndTargetTypeAndTargetId(ReportStatus.OPEN, ReportTargetType.PACKAGE_REQUEST,
                requestId)).thenReturn(true);
        assertThat(hold.holds(requestId)).isTrue();
    }

    @Test
    void resolvedReportOnly_doesNotHold() {
        // seul OPEN est interrogé : un signalement RESOLVED ne retient rien
        when(repository.existsByStatusAndTargetTypeAndTargetId(ReportStatus.RESOLVED, ReportTargetType.PACKAGE_REQUEST,
                requestId)).thenReturn(true);
        assertThat(hold.holds(requestId)).isFalse();
        assertThat(hold.holds(null)).isFalse();
    }
}
