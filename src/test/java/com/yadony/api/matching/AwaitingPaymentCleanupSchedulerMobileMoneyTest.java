package com.yadony.api.matching;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

/**
 * Le partage des bids {@code AWAITING_PAYMENT} entre les deux crons est une règle de requête,
 * pas un filtre Java : ce nettoyage carte demande au dépôt d'écarter le mobile money (expiré par
 * {@code MobileMoneyPaymentDeadlineScheduler}), et ne charge donc jamais ces lignes pour les
 * ignorer.
 */
@ExtendWith(MockitoExtension.class)
class AwaitingPaymentCleanupSchedulerMobileMoneyTest {

    @Mock BidRepository bidRepository;
    @Mock PaymentService paymentService;

    @Test
    void mobileMoneyBids_areExcludedByTheQuery_notInJava() throws Exception {
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of());

        new AwaitingPaymentCleanupScheduler(bidRepository, paymentService).cleanupUnpaidBids();

        verify(bidRepository).findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any());
        verify(paymentService, never()).cancelPaymentIntent(any());
        verify(bidRepository, never()).save(any());
    }
}
