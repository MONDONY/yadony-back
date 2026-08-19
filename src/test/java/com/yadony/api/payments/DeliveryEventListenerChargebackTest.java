package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.model.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that DeliveryEventListener blocks Stripe transfer when payment is under dispute.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerChargebackTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BidRepository bidRepository;
    @Mock private AdminAlertService adminAlert;
    @Mock private com.yadony.api.voucher.CommissionVoucherService voucherService;

    private DeliveryEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeliveryEventListener(paymentRepository, userRepository,
                auditService, eventPublisher, bidRepository, adminAlert, voucherService);
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field field = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    @Test
    void disputed_payment_blocks_transfer_and_raises_alert() throws Exception {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PaymentEntity payment = new PaymentEntity();
        setId(payment, paymentId);
        payment.setBidId(bidId);
        payment.setStripePaymentIntentId("pi_disputed");
        payment.setAmount(BigDecimal.valueOf(50));
        payment.setCommissionAmount(BigDecimal.valueOf(6));
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setLegacyDestinationCharge(false);
        payment.setDisputed(true);

        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        DeliveryConfirmedEvent event = new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event);

            // No Stripe transfer should happen
            transferStatic.verifyNoInteractions();
        }

        // Payment status must remain ESCROW (not released)
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);

        // Audit entry for blocked transfer
        verify(auditService).log(
                eq("PAYMENT"), any(), eq("DELIVERY_TRANSFER_BLOCKED_CHARGEBACK"), any(), anyMap());

        // Admin alert must be raised
        verify(adminAlert).raise(eq("CHARGEBACK_TRANSFER_BLOCKED"), anyString(), anyMap());

        // No PaymentReleasedEvent should be published
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }
}
