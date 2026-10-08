package com.yadony.api.disputes;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisputeMediaRetentionHoldTest {

    DisputeRepository repository = mock(DisputeRepository.class);
    DisputeMediaRetentionHold hold = new DisputeMediaRetentionHold(repository);

    @Test
    void unresolvedDispute_holdsThePhotos() {
        UUID bidId = UUID.randomUUID();
        when(repository.existsByBidIdAndStatusNot(bidId, "RESOLVED")).thenReturn(true);
        assertThat(hold.holds(bidId, List.of())).isTrue();
    }

    @Test
    void noOpenDispute_orNoBid_doesNotHold() {
        UUID bidId = UUID.randomUUID();
        when(repository.existsByBidIdAndStatusNot(bidId, "RESOLVED")).thenReturn(false);
        assertThat(hold.holds(bidId, List.of(UUID.randomUUID()))).isFalse();
        assertThat(hold.holds(null, List.of())).isFalse();
    }
}
