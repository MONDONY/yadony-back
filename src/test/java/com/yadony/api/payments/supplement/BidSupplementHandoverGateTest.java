package com.yadony.api.payments.supplement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BidSupplementHandoverGateTest {

    private final BidSupplementRepository supplements = mock(BidSupplementRepository.class);
    private final BidSupplementHandoverGate gate = new BidSupplementHandoverGate(supplements);

    @Test
    void demandeOuverte_bloqueLaRemise() {
        UUID bidId = UUID.randomUUID();
        when(supplements.existsByBidIdAndStatusIn(bidId, BidSupplementStatus.OPEN)).thenReturn(true);
        assertThat(gate.blockingCode(bidId)).contains("handover-supplement-pending");
    }

    @Test
    void aucuneDemandeOuverte_laisseLaRemise() {
        assertThat(gate.blockingCode(UUID.randomUUID())).isEmpty();
    }
}
