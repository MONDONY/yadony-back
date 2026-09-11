package com.yadony.api.requests.entity;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class NegotiationThreadStatusTest {

    @Test
    void awaitingDeposit_isActive_soAConcurrentSealAutoRejectsIt() {
        assertThat(NegotiationThreadStatus.AWAITING_DEPOSIT.isActive()).isTrue();
    }

    @Test
    void terminalStatuses_stayInactive() {
        assertThat(NegotiationThreadStatus.ACCEPTED.isActive()).isFalse();
        assertThat(NegotiationThreadStatus.CANCELLED.isActive()).isFalse();
        assertThat(NegotiationThreadStatus.AUTO_REJECTED.isActive()).isFalse();
    }
}
