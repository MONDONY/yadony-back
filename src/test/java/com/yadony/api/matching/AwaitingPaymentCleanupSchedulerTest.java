package com.yadony.api.matching;

import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.cash.PaymentMethod;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwaitingPaymentCleanupSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private PaymentService paymentService;

    private AwaitingPaymentCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AwaitingPaymentCleanupScheduler(bidRepository, paymentService);
    }

    private BidEntity expired(String piId) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(BidStatus.AWAITING_PAYMENT);
        b.setPaymentIntentId(piId);
        b.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        return b;
    }

    @Test
    void deletes_bid_when_cancel_succeeds() throws StripeException {
        BidEntity bid = expired("pi_xxx");
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of(bid));

        scheduler.cleanupUnpaidBids();

        verify(paymentService).cancelPaymentIntent("pi_xxx");
        assertThat(bid.getDeletedAt()).isNotNull();
        verify(bidRepository).save(bid);
        verify(paymentService, never()).promoteBidOnPaymentAuthorized(any());
    }

    @Test
    void promotes_when_PI_already_succeeded_race_condition() throws StripeException {
        BidEntity bid = expired("pi_race");
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of(bid));

        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("payment_intent_unexpected_state");
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_race");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            // requires_capture or succeeded both should trigger promotion
            when(pi.getStatus()).thenReturn("requires_capture");
            piStatic.when(() -> PaymentIntent.retrieve("pi_race")).thenReturn(pi);

            scheduler.cleanupUnpaidBids();
        }

        verify(paymentService).promoteBidOnPaymentAuthorized("pi_race");
        assertThat(bid.getDeletedAt()).isNull();
    }

    @Test
    void leaves_bid_alone_on_generic_stripe_error() throws StripeException {
        BidEntity bid = expired("pi_err");
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of(bid));

        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("rate_limit");
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_err");

        scheduler.cleanupUnpaidBids();  // should NOT throw

        assertThat(bid.getDeletedAt()).isNull();
        verify(paymentService, never()).promoteBidOnPaymentAuthorized(any());
    }

    @Test
    void deletes_bid_when_PI_is_canceled() throws StripeException {
        BidEntity bid = expired("pi_canceled");
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of(bid));

        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("payment_intent_unexpected_state");
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_canceled");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("canceled"); // payment was already canceled
            piStatic.when(() -> PaymentIntent.retrieve("pi_canceled")).thenReturn(pi);

            scheduler.cleanupUnpaidBids();
        }

        // Soft-delete the bid since payment is already canceled
        assertThat(bid.getDeletedAt()).isNotNull();
        verify(bidRepository).save(bid);
        verify(paymentService, never()).promoteBidOnPaymentAuthorized(any());
    }

    @Test
    void retries_when_unexpected_state_with_unknown_PI_status() throws StripeException {
        BidEntity bid = expired("pi_unknown");
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of(bid));

        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("payment_intent_unexpected_state");
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_unknown");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("unknown_status"); // unknown state
            piStatic.when(() -> PaymentIntent.retrieve("pi_unknown")).thenReturn(pi);

            scheduler.cleanupUnpaidBids();
        }

        // Should not soft-delete or promote when status is unknown (retry next time)
        assertThat(bid.getDeletedAt()).isNull();
        verify(paymentService, never()).promoteBidOnPaymentAuthorized(any());
    }

    @Test
    void leaves_bid_alone_when_retrieve_throws_in_race_check() throws StripeException {
        BidEntity bid = expired("pi_throw");
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of(bid));

        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("payment_intent_unexpected_state");
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_throw");

        InvalidRequestException retrieveEx = mock(InvalidRequestException.class);
        when(retrieveEx.getMessage()).thenReturn("retrieve failed");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_throw")).thenThrow(retrieveEx);

            scheduler.cleanupUnpaidBids();
        }

        verify(paymentService, never()).promoteBidOnPaymentAuthorized(any());
        assertThat(bid.getDeletedAt()).isNull();
    }

    @Test
    void no_op_when_no_expired_bids() {
        when(bidRepository.findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any())).thenReturn(List.of());

        scheduler.cleanupUnpaidBids();

        verifyNoInteractions(paymentService);
        verify(bidRepository, never()).save(any());
    }
}
