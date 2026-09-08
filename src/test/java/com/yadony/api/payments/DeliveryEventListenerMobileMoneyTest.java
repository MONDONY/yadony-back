package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.voucher.CommissionVoucherService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerMobileMoneyTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BidRepository bidRepository;
    @Mock AdminAlertService adminAlert;
    @Mock CommissionVoucherService voucherService;
    @Mock MobileMoneyPayoutInitiator payoutInitiator;

    private DeliveryEventListener listener;
    private PaymentEntity payment;
    private BidEntity bid;
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Ronde 1, point 5 : payoutInitiator est désormais un paramètre constructeur (jamais un
        // champ contournable) — plus besoin de ReflectionTestUtils pour l'injecter.
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService, eventPublisher,
                bidRepository, adminAlert, voucherService, payoutInitiator);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bid.getId());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        payment.setCurrency("XOF");
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(payment));
    }

    private DeliveryConfirmedEvent event() {
        return new DeliveryConfirmedEvent(bid.getId(), UUID.randomUUID(), travelerId);
    }

    @Test
    void pawapayRail_claimsOnce_thenReleasesNetThroughInitiator_withoutStripeAndWithoutReleasedEvent() {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(voucherService.consume(travelerId, bid.getId())).thenReturn(Optional.empty());

        listener.handleDeliveryConfirmed(event());

        verify(payoutInitiator).release(eq(payment), eq(bid.getId()), eq(travelerId), eq(new BigDecimal("15000")), eq("delivery"));
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void secondDeliveryEvent_doesNotReleaseTwice() {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1).thenReturn(0);
        when(voucherService.consume(any(), any())).thenReturn(Optional.empty());

        listener.handleDeliveryConfirmed(event());
        listener.handleDeliveryConfirmed(event());

        verify(payoutInitiator, org.mockito.Mockito.times(1)).release(any(), any(), any(), any(), any());
    }

    @Test
    void initiatorFailure_propagates_soTheClaimRollsBack() {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(voucherService.consume(any(), any())).thenReturn(Optional.empty());
        when(payoutInitiator.release(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("INSUFFICIENT_BALANCE"));

        assertThatThrownBy(() -> listener.handleDeliveryConfirmed(event())).isInstanceOf(IllegalStateException.class);
    }
}
