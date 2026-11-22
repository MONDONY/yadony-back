package com.yadony.api.payments.fee;

import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Réponse du paquet {@code payments} à la renonciation : un remboursement de l'expéditeur est-il déjà en route ? */
@ExtendWith(MockitoExtension.class)
class SenderRefundInFlightAdapterTest {

    private static final UUID BID_ID = UUID.randomUUID();

    @Mock PaymentRepository payments;
    @Mock PawapayOperationService operations;

    private SenderRefundInFlightAdapter adapter;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        adapter = new SenderRefundInFlightAdapter(payments, operations);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(BID_ID);
        payment.setRail(PaymentRail.PAWAPAY);
    }

    @Test
    void mobileMoney_liveOrCompletedRefund_inFlight() {
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));
        PawapayOperationEntity refund = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND,
                payment.getId(), null, new BigDecimal("45922"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        refund.setStatus(PawapayOperationStatus.CREATED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.of(refund));

        assertThat(adapter.senderRefundInFlight(BID_ID)).isTrue();
    }

    @Test
    void mobileMoney_noLiveRefund_notInFlight() {
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));
        when(operations.findLive(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.empty());

        assertThat(adapter.senderRefundInFlight(BID_ID)).isFalse();
    }

    @Test
    void card_neverInFlight_noPawapayLookup() {
        payment.setRail(PaymentRail.STRIPE);
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));

        assertThat(adapter.senderRefundInFlight(BID_ID)).isFalse();
        verifyNoInteractions(operations);
    }

    @Test
    void noPayment_notInFlight() {
        when(payments.findForBid(BID_ID)).thenReturn(Optional.empty());

        assertThat(adapter.senderRefundInFlight(BID_ID)).isFalse();
        verifyNoInteractions(operations);
    }
}
