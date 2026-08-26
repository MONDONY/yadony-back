package com.yadony.api.payments;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentDeletionImpactContributorTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock PaymentRepository paymentRepository;

    @Test
    @DisplayName("un escrow actif est un constat bloquant")
    void activeEscrow_isBlocking() {
        when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);

        List<ImpactFinding> findings =
                new PaymentDeletionImpactContributor(paymentRepository).contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().severity()).isEqualTo(ImpactSeverity.BLOCKING);
        assertThat(findings.getFirst().code()).isEqualTo("ACTIVE_ESCROW");
    }

    @Test
    @DisplayName("sans escrow actif, rien n'est rapporté")
    void noEscrow_reportsNothing() {
        when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);

        assertThat(new PaymentDeletionImpactContributor(paymentRepository).contribute(USER_ID))
                .isEmpty();
    }
}
