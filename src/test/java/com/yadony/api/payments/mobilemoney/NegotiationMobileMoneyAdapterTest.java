package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.requests.NegotiationMobileMoneyPort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L'adaptateur ne fait que déléguer au service : ces trois tests vérifient uniquement le
 * passage des arguments et le retour de la valeur du service, sans logique propre à tester.
 */
@ExtendWith(MockitoExtension.class)
class NegotiationMobileMoneyAdapterTest {

    @Mock
    private MobileMoneyNegotiationPaymentService service;

    @InjectMocks
    private NegotiationMobileMoneyAdapter adapter;

    private final UUID threadId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @Test
    @DisplayName("createPendingDeposit délègue à service.createPendingPayment avec les mêmes arguments")
    void createPendingDeposit_delegates() {
        BigDecimal net = new BigDecimal("35.00");
        BigDecimal rate = new BigDecimal("0.12");
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        NegotiationMobileMoneyPort.PendingDeposit expected =
            new NegotiationMobileMoneyPort.PendingDeposit(UUID.randomUUID(), net, new BigDecimal("4.20"), expiresAt);
        when(service.createPendingPayment(threadId, senderId, travelerId, net, rate, "XOF")).thenReturn(expected);

        NegotiationMobileMoneyPort.PendingDeposit result =
            adapter.createPendingDeposit(threadId, senderId, travelerId, net, rate, "XOF");

        assertThat(result).isSameAs(expected);
        verify(service).createPendingPayment(threadId, senderId, travelerId, net, rate, "XOF");
    }

    @Test
    @DisplayName("releasePendingDeposit délègue à service.releasePendingDeposit")
    void releasePendingDeposit_delegates() {
        when(service.releasePendingDeposit(threadId))
            .thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_OPEN);

        NegotiationMobileMoneyPort.ReleaseOutcome result = adapter.releasePendingDeposit(threadId);

        assertThat(result).isEqualTo(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_OPEN);
        verify(service).releasePendingDeposit(threadId);
    }

    @Test
    @DisplayName("refundEscrowedDeposit délègue à service.refundEscrowedDeposit")
    void refundEscrowedDeposit_delegates() {
        when(service.refundEscrowedDeposit(threadId)).thenReturn(true);

        boolean result = adapter.refundEscrowedDeposit(threadId);

        assertThat(result).isTrue();
        verify(service).refundEscrowedDeposit(threadId);
    }
}
