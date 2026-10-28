package com.yadony.api.payments;

import com.yadony.api.payments.overview.MoneyRole;
import com.yadony.api.payments.overview.MoneyRow;
import com.yadony.api.payments.overview.TravelerParcelNet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelerParcelsNetAdapterTest {

    private final PaymentRepository repo = mock(PaymentRepository.class);
    private final TravelerParcelsNetAdapter adapter = new TravelerParcelsNetAdapter(repo);

    private static PaymentEntity payment(UUID bidId, String amount, String commission, String refunded,
                                         PaymentStatus status) {
        PaymentEntity p = mock(PaymentEntity.class);
        when(p.getBidId()).thenReturn(bidId);
        when(p.getAmount()).thenReturn(amount == null ? null : new BigDecimal(amount));
        when(p.getCommissionAmount()).thenReturn(new BigDecimal(commission));
        when(p.getRefundedAmount()).thenReturn(refunded == null ? null : new BigDecimal(refunded));
        when(p.getStatus()).thenReturn(status);
        return p;
    }

    /** Ce que « Mon argent » montrerait au voyageur pour la même ligne (MoneyStateResolver). */
    private static BigDecimal monArgent(String amount, String commission, String refunded, PaymentStatus status) {
        MoneyRow row = new MoneyRow(MoneyRole.TRAVELER, UUID.randomUUID(), "IN_TRANSIT", "CARD", "EUR", null,
                null, null, UUID.randomUUID(), null, null, null, null, null, UUID.randomUUID(), status.name(),
                "STRIPE", new BigDecimal(amount), new BigDecimal(commission),
                refunded == null ? null : new BigDecimal(refunded), "EUR", false, null, null, null, 0, 0, null, 0, 0);
        return com.yadony.api.payments.overview.MoneyOverviewTestAccess.amountOrZero(row);
    }

    @Test
    void sameFigureAsMonArgentForPaidRefundedAndPartiallyRefunded() {
        record Case(String amount, String commission, String refunded, PaymentStatus status) {}
        for (Case c : List.of(
                new Case("100.00", "12.00", null, PaymentStatus.ESCROW),
                new Case("100.00", "12.00", "100.00", PaymentStatus.REFUNDED),
                new Case("100.00", "12.00", "30.00", PaymentStatus.ESCROW))) {
            UUID bid = UUID.randomUUID();
            PaymentEntity p = payment(bid, c.amount(), c.commission(), c.refunded(), c.status());
            when(repo.findByBidIdIn(any())).thenReturn(List.of(p));
            assertThat(adapter.estimatedNet(List.of(bid)))
                    .as(c.toString())
                    .isEqualByComparingTo(monArgent(c.amount(), c.commission(), c.refunded(), c.status()))
                    .isEqualByComparingTo(TravelerParcelNet.of(c.status().name(), new BigDecimal(c.amount()),
                            new BigDecimal(c.commission()),
                            c.refunded() == null ? null : new BigDecimal(c.refunded())));
        }
    }

    @Test
    void cashParcelCountsForZeroAndNegotiatedPaymentsAreReadInOneBatch() {
        UUID direct = UUID.randomUUID(), cash = UUID.randomUUID(), negotiated = UUID.randomUUID();
        PaymentEntity paid = payment(direct, "100.00", "12.00", null, PaymentStatus.ESCROW);
        PaymentEntity linked = payment(null, "20.00", "2.00", null, PaymentStatus.ESCROW);
        when(repo.findByBidIdIn(any())).thenReturn(List.of(paid));
        when(repo.findLinkedNegotiationPaymentsOfBids(any()))
                .thenReturn(List.<Object[]>of(new Object[] {negotiated, linked}));

        assertThat(adapter.estimatedNet(List.of(direct, cash, negotiated))).isEqualByComparingTo("106");
        verify(repo, never()).findLinkedNegotiationPaymentOfBid(any());
    }

    @Test
    void nullAmountPaymentIsZeroAndEmptyInputIsZero() {
        UUID bid = UUID.randomUUID();
        PaymentEntity p = payment(bid, null, "0", null, PaymentStatus.ESCROW);
        when(repo.findByBidIdIn(any())).thenReturn(List.of(p));
        when(repo.findLinkedNegotiationPaymentsOfBids(any())).thenReturn(List.of());
        assertThat(adapter.estimatedNet(List.of(bid))).isEqualByComparingTo("0");
        assertThat(adapter.estimatedNet(List.of())).isEqualByComparingTo("0");
    }
}
