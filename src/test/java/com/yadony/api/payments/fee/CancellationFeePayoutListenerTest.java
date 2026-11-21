package com.yadony.api.payments.fee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Retenue réglée au remboursement : le net part au voyageur juste après le commit. */
@ExtendWith(MockitoExtension.class)
class CancellationFeePayoutListenerTest {

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID FEE_ID = UUID.randomUUID();

    @Mock CancellationFeePayoutPort port;
    @Mock CancellationFeePayoutService payoutService;

    private CancellationFeePayoutListener listener() {
        return new CancellationFeePayoutListener(port, payoutService, TransactionOperations.withoutTransaction());
    }

    @Test
    void retainedFee_isPaidOut() {
        when(port.findPayableByBid(BID_ID)).thenReturn(Optional.of(new PayableCancellationFee(FEE_ID, BID_ID,
                UUID.randomUUID(), new BigDecimal("26.84"), "EUR", PayableCancellationFee.Status.RETAINED)));

        listener().onFeeRetained(new CancellationFeeRetainedEvent(BID_ID));

        verify(payoutService).payOut(FEE_ID);
    }

    @Test
    void nothingToPay_noPayout() {
        when(port.findPayableByBid(BID_ID)).thenReturn(Optional.empty());

        listener().onFeeRetained(new CancellationFeeRetainedEvent(BID_ID));

        verifyNoInteractions(payoutService);
    }

    @Test
    void lookupFailure_neverThrows_theSchedulerTakesOver() {
        when(port.findPayableByBid(BID_ID)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener().onFeeRetained(new CancellationFeeRetainedEvent(BID_ID)))
                .doesNotThrowAnyException();
        verifyNoInteractions(payoutService);
    }
}
