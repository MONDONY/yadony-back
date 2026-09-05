package com.yadony.api.matching;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.cash.PaymentMethod;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AwaitingPaymentCleanupSchedulerMobileMoneyTest {

    @Mock BidRepository bidRepository;
    @Mock PaymentService paymentService;

    @Test
    void mobileMoneyBids_areLeftToTheirOwnScheduler() throws Exception {
        BidEntity mm = new BidEntity();
        mm.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        mm.setStatus(BidStatus.AWAITING_PAYMENT);
        when(bidRepository.findByStatusAndAwaitingPaymentExpiresAtBefore(any(), any())).thenReturn(List.of(mm));

        new AwaitingPaymentCleanupScheduler(bidRepository, paymentService).cleanupUnpaidBids();

        verify(paymentService, never()).cancelPaymentIntent(any());
        verify(bidRepository, never()).save(any());
    }
}
