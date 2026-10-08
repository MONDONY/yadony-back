package com.yadony.api.signalements;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportMediaRetentionHoldTest {

    ReportRepository repository = mock(ReportRepository.class);
    ReportMediaRetentionHold hold = new ReportMediaRetentionHold(repository);
    UUID bidId = UUID.randomUUID();
    UUID convId = UUID.randomUUID();

    @Test
    void openReportOnTheBid_holds() {
        when(repository.existsByStatusAndTargetTypeAndTargetId(ReportStatus.OPEN, ReportTargetType.BID, bidId))
                .thenReturn(true);
        assertThat(hold.holds(bidId, List.of(convId))).isTrue();
    }

    @Test
    void openReportOnAMessageOfTheConversations_holds() {
        when(repository.existsByStatusAndTargetTypeAndTargetIdIn(ReportStatus.OPEN, ReportTargetType.MESSAGE,
                List.of(convId))).thenReturn(true);
        assertThat(hold.holds(bidId, List.of(convId))).isTrue();
    }

    @Test
    void nothingOpen_doesNotHold() {
        assertThat(hold.holds(bidId, List.of(convId))).isFalse();
        assertThat(hold.holds(null, List.of())).isFalse();
        assertThat(hold.holds(null, null)).isFalse();
    }
}
