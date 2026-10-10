package com.yadony.api.payments;

import com.stripe.exception.InvalidRequestException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LateAuthorizationReleaserTest {

    @Mock StripeGateway stripeGateway;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertEscalator alerts;

    LateAuthorizationReleaser releaser;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        releaser = new LateAuthorizationReleaser(stripeGateway, paymentRepository, auditService, alerts);
    }

    private PaymentIntent intent(String status) throws Exception {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn(status);
        when(stripeGateway.retrievePaymentIntent("pi_1")).thenReturn(pi);
        return pi;
    }

    @Test
    void authorized_isReleased_andAudited() throws Exception {
        PaymentIntent pi = intent("requires_capture");

        assertThat(releaser.release(paymentId, "pi_1", bidId)).isTrue();

        verify(pi).cancel(any(PaymentIntentCancelParams.class));
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq(LateAuthorizationReleaser.AUDIT_RELEASED),
                eq(null), anyMap());
        verifyNoInteractions(alerts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"canceled", "processing", "requires_payment_method"})
    void nothingHeld_noAction(String status) throws Exception {
        PaymentIntent pi = intent(status);

        assertThat(releaser.release(paymentId, "pi_1", bidId)).isFalse();

        verify(pi, never()).cancel(any(PaymentIntentCancelParams.class));
        verifyNoInteractions(auditService, alerts);
    }

    @Test
    void captured_isRefundedWithTheSharedIdempotencyKey_andAlerts() throws Exception {
        intent("succeeded");
        try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
            refund.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(releaser.release(paymentId, "pi_1", bidId)).isTrue();

            refund.verify(() -> Refund.create(any(RefundCreateParams.class),
                    org.mockito.ArgumentMatchers.<RequestOptions>argThat(
                            o -> ("refund-" + paymentId).equals(o.getIdempotencyKey()))));
        }
        verify(paymentRepository).markRefundedIfCancelled(paymentId);
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq(LateAuthorizationReleaser.AUDIT_REFUNDED),
                eq(null), anyMap());
        verify(alerts).raiseOnce(eq(LateAuthorizationReleaser.REFUND_ALERT_PREFIX + paymentId), anyString(), anyMap());
    }

    @Test
    void refundFailure_alertsAndThrows() throws Exception {
        intent("succeeded");
        try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
            refund.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(stripeError());

            assertThatThrownBy(() -> releaser.release(paymentId, "pi_1", bidId))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(alerts).raiseOnce(eq(LateAuthorizationReleaser.REFUND_ALERT_PREFIX + paymentId), anyString(), anyMap());
        verify(paymentRepository, never()).markRefundedIfCancelled(any());
    }

    @Test
    void cancelFailure_throwsSoTheWebhookIsReplayed() throws Exception {
        PaymentIntent pi = intent("requires_capture");
        when(pi.cancel(any(PaymentIntentCancelParams.class))).thenThrow(stripeError());

        assertThatThrownBy(() -> releaser.release(paymentId, "pi_1", bidId))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(auditService);
    }

    @Test
    void stripeUnreachable_throws() throws Exception {
        when(stripeGateway.retrievePaymentIntent("pi_1")).thenThrow(stripeError());

        assertThatThrownBy(() -> releaser.release(paymentId, "pi_1", bidId))
                .isInstanceOf(IllegalStateException.class);
    }

    private static InvalidRequestException stripeError() {
        return new InvalidRequestException("boom", null, "x", null, 400, null);
    }
}
