package com.yadony.api.payments.fee;

import com.yadony.api.cancellation.fee.CancellationFeeQuotePort;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Réponse du paquet {@code payments} à l'aperçu des frais : montant payé du colis, et brut que le remboursement
 * retiendrait maintenant, par le même calcul que le règlement ({@link CancellationFeeSettlement#preview}).
 */
@ExtendWith(MockitoExtension.class)
class CancellationFeeQuoteAdapterTest {

    private static final UUID BID_ID = UUID.randomUUID();
    private static final BigDecimal RATE = new BigDecimal("0.20");

    @Mock PaymentRepository payments;
    @Mock PawapayOperationService operations;
    @Mock CancellationFeeSettlement settlement;

    private CancellationFeeQuoteAdapter adapter;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        adapter = new CancellationFeeQuoteAdapter(payments, operations, settlement);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(BID_ID);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCurrency("EUR");
        payment.setStatus(PaymentStatus.ESCROW);
    }

    @Test
    void card_quoteOfTheSettlementPreview() {
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));
        when(settlement.preview(payment, RATE, null)).thenReturn(
                new CancellationFeeSettlement.Preview(true, new BigDecimal("20.00"), new BigDecimal("80.00")));

        Optional<CancellationFeeQuotePort.Quote> quote = adapter.quote(BID_ID, RATE);

        assertThat(quote).hasValueSatisfying(q -> {
            assertThat(q.paid()).isEqualByComparingTo("100.00");
            assertThat(q.currency()).isEqualTo("EUR");
            assertThat(q.retainable()).isTrue();
            assertThat(q.grossFee()).isEqualByComparingTo("20.00");
            assertThat(q.refundAmount()).isEqualByComparingTo("80.00");
        });
        verifyNoInteractions(operations);
    }

    @Test
    void mobileMoney_completedDepositAmountHandedToThePreview() {
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setAmount(new BigDecimal("65600"));
        payment.setCurrency("XOF");
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(deposit(PawapayOperationStatus.COMPLETED, "65600")));
        when(settlement.preview(payment, RATE, new BigDecimal("65600"))).thenReturn(
                new CancellationFeeSettlement.Preview(true, new BigDecimal("13120"), new BigDecimal("52480")));

        assertThat(adapter.quote(BID_ID, RATE)).hasValueSatisfying(q -> {
            assertThat(q.currency()).isEqualTo("XOF");
            assertThat(q.grossFee()).isEqualByComparingTo("13120");
        });
    }

    @Test
    void mobileMoney_depositNotCompleted_noDepositAmount() {
        payment.setRail(PaymentRail.PAWAPAY);
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(deposit(PawapayOperationStatus.PROCESSING, "100.00")));
        when(settlement.preview(payment, RATE, null)).thenReturn(
                new CancellationFeeSettlement.Preview(false, BigDecimal.ZERO, new BigDecimal("100.00")));

        assertThat(adapter.quote(BID_ID, RATE)).hasValueSatisfying(q -> assertThat(q.retainable()).isFalse());
        verify(settlement).preview(payment, RATE, null);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "RELEASED", "REFUNDED", "FAILED", "CANCELLED"})
    void paymentNotInEscrow_nothingToRefund_empty(PaymentStatus status) {
        payment.setStatus(status);
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));

        assertThat(adapter.quote(BID_ID, RATE)).isEmpty();
        verifyNoInteractions(settlement, operations);
    }

    @Test
    void noPayment_empty() {
        when(payments.findForBid(BID_ID)).thenReturn(Optional.empty());

        assertThat(adapter.quote(BID_ID, RATE)).isEmpty();
        verifyNoInteractions(settlement, operations);
    }

    private PawapayOperationEntity deposit(PawapayOperationStatus status, String amount) {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                payment.getId(), null, new BigDecimal(amount), payment.getCurrency(), "ORANGE_SEN", "SN", "221771234567");
        op.setStatus(status);
        return op;
    }
}
