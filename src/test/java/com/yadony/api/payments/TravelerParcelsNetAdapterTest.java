package com.yadony.api.payments;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TravelerParcelsNetAdapterTest {

    private final PaymentRepository repo = mock(PaymentRepository.class);
    private final TravelerParcelsNetAdapter adapter = new TravelerParcelsNetAdapter(repo);

    private static PaymentEntity payment(UUID bidId, String amount, String commission, PaymentStatus status) {
        PaymentEntity p = mock(PaymentEntity.class);
        when(p.getBidId()).thenReturn(bidId);
        when(p.getAmount()).thenReturn(new BigDecimal(amount));
        when(p.getCommissionAmount()).thenReturn(new BigDecimal(commission));
        when(p.getStatus()).thenReturn(status);
        return p;
    }

    @Test
    void sumsAmountMinusCommissionSkippingRefundedAndCash() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), cash = UUID.randomUUID(), negotiated = UUID.randomUUID();
        List<PaymentEntity> paid = List.of(
                payment(a, "100.00", "12.00", PaymentStatus.ESCROW),
                payment(b, "50.00", "6.00", PaymentStatus.REFUNDED));
        PaymentEntity linked = payment(null, "20.00", "2.00", PaymentStatus.ESCROW);
        when(repo.findByBidIdIn(any())).thenReturn(paid);
        when(repo.findLinkedNegotiationPaymentOfBid(cash)).thenReturn(Optional.empty());
        when(repo.findLinkedNegotiationPaymentOfBid(negotiated)).thenReturn(Optional.of(linked));

        assertThat(adapter.estimatedNet(List.of(a, b, cash, negotiated))).isEqualByComparingTo("106");
    }

    @Test
    void emptyInputIsZero() {
        assertThat(adapter.estimatedNet(List.of())).isEqualByComparingTo("0");
    }
}
