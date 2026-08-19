package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookChargeIdTest {

    @Mock private BidRepository bidRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(com.yadony.api.common.stripe.AdminAlertService.class), PaymentServiceTestFactory.stubbedResolver(), org.mockito.Mockito.mock(com.yadony.api.promo.PromoService.class), new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(), mock(com.yadony.api.voucher.CommissionVoucherService.class), mock(com.yadony.api.payments.ConnectAccountProvisioner.class)
);
    }

    @Test
    void persists_charge_id_when_present_on_pi() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_123");
        when(pi.getLatestCharge()).thenReturn("ch_abc");
        when(pi.getAmountCapturable()).thenReturn(3000L);

        PaymentEntity payment = new PaymentEntity();
        payment.setBidId(UUID.randomUUID());
        payment.setStripePaymentIntentId("pi_123");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("30.00"));
        payment.setCommissionAmount(new BigDecimal("3.60"));

        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));

        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        lenient().when(event.getType()).thenReturn("payment_intent.amount_capturable_updated");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(pi));

        service.handlePaymentEscrowActive(event);

        assertThat(payment.getStripeChargeId()).isEqualTo("ch_abc");
        verify(paymentRepository).save(payment);
    }

    @Test
    void leaves_charge_id_null_when_PI_has_none() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_456");
        when(pi.getLatestCharge()).thenReturn(null);
        when(pi.getAmountCapturable()).thenReturn(3000L);

        PaymentEntity payment = new PaymentEntity();
        payment.setBidId(UUID.randomUUID());
        payment.setStripePaymentIntentId("pi_456");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("30.00"));
        payment.setCommissionAmount(new BigDecimal("3.60"));

        when(paymentRepository.findByStripePaymentIntentId("pi_456")).thenReturn(Optional.of(payment));

        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        lenient().when(event.getType()).thenReturn("payment_intent.amount_capturable_updated");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(pi));

        service.handlePaymentEscrowActive(event);

        assertThat(payment.getStripeChargeId()).isNull();
    }

    @Test
    void does_not_overwrite_existing_charge_id() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_789");
        when(pi.getLatestCharge()).thenReturn("ch_NEW");
        when(pi.getAmountCapturable()).thenReturn(3000L);

        PaymentEntity payment = new PaymentEntity();
        payment.setBidId(UUID.randomUUID());
        payment.setStripePaymentIntentId("pi_789");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("30.00"));
        payment.setCommissionAmount(new BigDecimal("3.60"));
        payment.setStripeChargeId("ch_OLD");

        when(paymentRepository.findByStripePaymentIntentId("pi_789")).thenReturn(Optional.of(payment));

        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        lenient().when(event.getType()).thenReturn("payment_intent.amount_capturable_updated");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(pi));

        service.handlePaymentEscrowActive(event);

        assertThat(payment.getStripeChargeId()).isEqualTo("ch_OLD");
    }
}
