package com.yadony.api.payments.fee;

import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue pawaPay d'un versement de dédommagement (purpose {@code CANCELLATION_FEE}) : la retenue passe en
 * {@code PAID_OUT} à la confirmation, en attente à l'échec. Les opérations du paiement du colis (livraison,
 * remboursement, dépôt) ne la concernent jamais.
 */
@ExtendWith(MockitoExtension.class)
class CancellationFeePayoutOutcomeListenerTest {

    @Mock PawapayOperationService operations;
    @Mock CancellationFeePayoutService payouts;

    private CancellationFeePayoutOutcomeListener listener;
    private PawapayOperationEntity payout;
    private final UUID feeId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CancellationFeePayoutOutcomeListener(operations, payouts);
        payout = PawapayOperationEntity.forCancellationFee(UUID.randomUUID(), travelerId, feeId,
                new BigDecimal("17323"), "XOF", "ORANGE_SEN", "SN", "221770000002");
    }

    @Test
    void completedFeePayout_marksTheFeePaidOut() {
        when(operations.get(payout.getId())).thenReturn(payout);

        listener.onCompleted(new PawapayOperationCompletedEvent(payout.getId(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.CANCELLATION_FEE, null, travelerId));

        verify(payouts).mobileMoneyPayoutCompleted(feeId, payout.getId());
    }

    @Test
    void failedFeePayout_leavesTheFeePending_withTheBoundedFailureCode() {
        when(operations.get(payout.getId())).thenReturn(payout);

        listener.onFailed(new PawapayOperationFailedEvent(payout.getId(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.CANCELLATION_FEE, null, travelerId, "RECIPIENT_NOT_FOUND", "inconnu"));

        verify(payouts).mobileMoneyPayoutFailed(feeId, "RECIPIENT_NOT_FOUND");
    }

    @Test
    void deliveryPayoutOfTheParcel_isNeverAFeeOutcome() {
        UUID paymentId = UUID.randomUUID();

        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.BID_PAYMENT, paymentId, null));
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.BID_PAYMENT, paymentId, null, "X", "Y"));
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.REFUND,
                PawapayOperationPurpose.BID_PAYMENT, paymentId, null));

        verifyNoInteractions(operations, payouts);
    }

    @Test
    void feeOperationWithoutItsFee_isIgnored() {
        PawapayOperationEntity orphan = PawapayOperationEntity.forCancellationFee(UUID.randomUUID(), travelerId, null,
                new BigDecimal("1"), "XOF", "ORANGE_SEN", "SN", "221770000002");
        when(operations.get(orphan.getId())).thenReturn(orphan);

        listener.onCompleted(new PawapayOperationCompletedEvent(orphan.getId(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.CANCELLATION_FEE, null, travelerId));

        verifyNoInteractions(payouts);
    }
}
