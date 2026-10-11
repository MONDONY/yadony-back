package com.yadony.api.matching;

import com.yadony.api.cancellation.CancellationService;
import com.yadony.api.common.TransientLockRetry;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.payments.PaymentBusy;
import com.yadony.api.payments.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** POST /bids/{id}/confirm-payment face à un interblocage (YADONY-BACK-STAGING-18). */
class BidControllerConfirmPaymentTest {

    private final BidService bidService = mock(BidService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final BidController controller = new BidController(bidService, mock(BidCheckoutService.class),
            paymentService, mock(CancellationService.class));
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-sender", null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deadlockThenSuccess_replaysInANewTransaction_andAnswersTheBid() {
        BidResponse body = mock(BidResponse.class);
        when(paymentService.confirmBidPayment(bidId))
                .thenThrow(new CannotAcquireLockException("deadlock detected"))
                .thenReturn(true);
        when(bidService.getBidById(bidId, "uid-sender")).thenReturn(body);

        assertThat(controller.confirmPayment(bidId).getBody()).isSameAs(body);
        verify(bidService).assertSenderOwnsBid(bidId, "uid-sender");
        verify(paymentService, times(2)).confirmBidPayment(bidId);
    }

    @Test
    void persistentDeadlock_answers409PaymentBusy_neverA500() {
        when(paymentService.confirmBidPayment(bidId)).thenThrow(new CannotAcquireLockException("deadlock detected"));

        assertThatThrownBy(() -> controller.confirmPayment(bidId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo(PaymentBusy.CODE);
                    assertThat(e.getMessage()).isEqualTo("Paiement en cours de validation, réessayez dans un instant");
                });
        verify(paymentService, times(TransientLockRetry.MAX_ATTEMPTS)).confirmBidPayment(bidId);
        verify(bidService, never()).getBidById(bidId, "uid-sender");
    }
}
