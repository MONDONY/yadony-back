package com.yadony.api.payments.chargeback;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChargebackServiceTest {

    @Mock ChargebackRepository chargebackRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlert;

    ChargebackService service;

    @BeforeEach
    void setUp() {
        service = new ChargebackService(chargebackRepository, paymentRepository,
                auditService, adminAlert, new ObjectMapper());
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field field = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private Event buildDisputeEvent(String type, String disputeId, String chargeId, String status) {
        String json = String.format(
            "{\"id\":\"evt_x\",\"object\":\"event\",\"type\":\"%s\"," +
            "\"data\":{\"object\":{\"id\":\"%s\",\"charge\":\"%s\"," +
            "\"amount\":1000,\"currency\":\"eur\",\"reason\":\"fraudulent\"," +
            "\"status\":\"%s\"}}}", type, disputeId, chargeId, status);
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    @Test
    void handleDisputeCreated_savesChargebackAndFlagsPayment() {
        String disputeId = "dp_test_001";
        String chargeId  = "ch_test_001";
        UUID paymentId   = UUID.randomUUID();
        UUID bidId       = UUID.randomUUID();

        PaymentEntity payment = new PaymentEntity();
        payment.setStripePaymentIntentId("pi_test");
        payment.setAmount(BigDecimal.valueOf(10));
        payment.setCommissionAmount(BigDecimal.valueOf(1.2));

        // Use reflection / setter to set id
        when(paymentRepository.findByStripeChargeId(chargeId)).thenReturn(Optional.of(payment));
        when(chargebackRepository.findByStripeDisputeId(disputeId)).thenReturn(Optional.empty());

        Event event = buildDisputeEvent("charge.dispute.created", disputeId, chargeId, "needs_response");
        service.handleDisputeCreated(event);

        // chargeback should be saved
        ArgumentCaptor<ChargebackEntity> captor = ArgumentCaptor.forClass(ChargebackEntity.class);
        verify(chargebackRepository).save(captor.capture());
        ChargebackEntity saved = captor.getValue();
        assertThat(saved.getStripeDisputeId()).isEqualTo(disputeId);
        assertThat(saved.getStripeChargeId()).isEqualTo(chargeId);
        assertThat(saved.getAmount()).isEqualTo(1000L);
        assertThat(saved.getStatus()).isEqualTo(ChargebackStatus.OPEN);

        // payment should be flagged as disputed
        assertThat(payment.isDisputed()).isTrue();
        verify(paymentRepository).save(payment);

        // admin alert should be raised
        verify(adminAlert).raise(eq("STRIPE_CHARGEBACK_OPENED"), anyString(), anyMap());

        // audit should be logged
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_DISPUTED"), any(), anyMap());
    }

    @Test
    void handleDisputeCreated_idempotent_skipsIfAlreadyRecorded() {
        String disputeId = "dp_duplicate";
        when(chargebackRepository.findByStripeDisputeId(disputeId))
                .thenReturn(Optional.of(new ChargebackEntity()));

        Event event = buildDisputeEvent("charge.dispute.created", disputeId, "ch_x", "needs_response");
        service.handleDisputeCreated(event);

        // No new save expected
        verify(chargebackRepository, never()).save(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void handleDisputeClosed_won_updatesStatusAndClearsPaymentDisputed() throws Exception {
        String disputeId = "dp_won_001";
        UUID paymentId   = UUID.randomUUID();
        UUID cbId        = UUID.randomUUID();

        ChargebackEntity cb = new ChargebackEntity();
        setId(cb, cbId);
        cb.setStripeDisputeId(disputeId);
        cb.setStatus(ChargebackStatus.OPEN);
        cb.setPaymentId(paymentId);
        cb.setOpenedAt(java.time.Instant.now());

        PaymentEntity payment = new PaymentEntity();
        payment.setDisputed(true);
        payment.setStripePaymentIntentId("pi_test");
        payment.setAmount(BigDecimal.valueOf(10));
        payment.setCommissionAmount(BigDecimal.valueOf(1.2));

        when(chargebackRepository.findByStripeDisputeId(disputeId)).thenReturn(Optional.of(cb));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        Event event = buildDisputeEvent("charge.dispute.closed", disputeId, "ch_x", "won");
        service.handleDisputeClosed(event);

        assertThat(cb.getStatus()).isEqualTo(ChargebackStatus.WON);
        assertThat(cb.getOutcome()).isEqualTo("won");
        assertThat(cb.getResolvedAt()).isNotNull();
        assertThat(payment.isDisputed()).isFalse();

        verify(chargebackRepository).save(cb);
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_DISPUTE_WON"), any(), anyMap());
        verify(auditService).log(eq("CHARGEBACK"), any(), eq("CHARGEBACK_CLOSED"), any(), anyMap());
        verify(adminAlert).raise(eq("STRIPE_CHARGEBACK_CLOSED"), anyString(), anyMap());
    }

    @Test
    void handleDisputeClosed_lost_updatesStatusAndClearsDisputed() {
        var cb = new ChargebackEntity();
        cb.setStripeDisputeId("dp_003");
        var payment = new PaymentEntity();
        payment.setDisputed(true);
        UUID paymentId = UUID.randomUUID();
        cb.setPaymentId(paymentId);

        when(chargebackRepository.findByStripeDisputeId("dp_003")).thenReturn(Optional.of(cb));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        String json = "{\"id\":\"evt_e\",\"object\":\"event\",\"type\":\"charge.dispute.closed\"," +
                "\"data\":{\"object\":{\"id\":\"dp_003\",\"status\":\"lost\"}}}";
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
        service.handleDisputeClosed(event);

        assertThat(cb.getStatus()).isEqualTo(ChargebackStatus.LOST);
        assertThat(payment.isDisputed()).isFalse();
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_DISPUTE_LOST"), any(), any());
    }

    @Test
    void handleFundsWithdrawn_logsAudit_whenDisputeFound() {
        String disputeId = "dp_funds_withdrawn";
        var cb = new ChargebackEntity();
        cb.setStripeDisputeId(disputeId);
        cb.setStatus(ChargebackStatus.OPEN);

        when(chargebackRepository.findByStripeDisputeId(disputeId)).thenReturn(java.util.Optional.of(cb));

        String json = String.format(
                "{\"id\":\"evt_fw\",\"object\":\"event\",\"type\":\"charge.dispute.funds_withdrawn\"," +
                "\"data\":{\"object\":{\"id\":\"%s\"}}}", disputeId);
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
        service.handleFundsWithdrawn(event);

        verify(auditService).log(eq("CHARGEBACK"), any(), eq("CHARGEBACK_FUNDS_WITHDRAWN"), isNull(), anyMap());
    }

    @Test
    void handleFundsReinstated_logsAudit_whenDisputeFound() {
        String disputeId = "dp_funds_reinstated";
        var cb = new ChargebackEntity();
        cb.setStripeDisputeId(disputeId);
        cb.setStatus(ChargebackStatus.WON);

        when(chargebackRepository.findByStripeDisputeId(disputeId)).thenReturn(java.util.Optional.of(cb));

        String json = String.format(
                "{\"id\":\"evt_fr\",\"object\":\"event\",\"type\":\"charge.dispute.funds_reinstated\"," +
                "\"data\":{\"object\":{\"id\":\"%s\"}}}", disputeId);
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
        service.handleFundsReinstated(event);

        verify(auditService).log(eq("CHARGEBACK"), any(), eq("CHARGEBACK_FUNDS_REINSTATED"), isNull(), anyMap());
    }

    @Test
    void handleFundsWithdrawn_doesNothing_whenDisputeNotFound() {
        when(chargebackRepository.findByStripeDisputeId(any())).thenReturn(java.util.Optional.empty());

        String json = "{\"id\":\"evt_nf\",\"object\":\"event\",\"type\":\"charge.dispute.funds_withdrawn\"," +
                "\"data\":{\"object\":{\"id\":\"dp_unknown\"}}}";
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
        service.handleFundsWithdrawn(event);

        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void handleDisputeCreated_withNullChargeId_stillSavesChargeback() {
        String disputeId = "dp_no_charge";
        when(chargebackRepository.findByStripeDisputeId(disputeId)).thenReturn(Optional.empty());

        // Event with charge=null
        String json = String.format(
                "{\"id\":\"evt_nc\",\"object\":\"event\",\"type\":\"charge.dispute.created\"," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"amount\":500,\"currency\":\"eur\"," +
                "\"reason\":\"fraudulent\",\"status\":\"needs_response\"}}}",
                disputeId);
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
        service.handleDisputeCreated(event);

        verify(chargebackRepository).save(any(ChargebackEntity.class));
        // No payment lookup when chargeId is null
        verify(paymentRepository, never()).findByStripeChargeId(any());
    }

    @Test
    void handleDisputeClosed_withNullPaymentId_doesNotLookupPayment() {
        String disputeId = "dp_no_payment";
        var cb = new ChargebackEntity();
        cb.setStripeDisputeId(disputeId);
        cb.setStatus(ChargebackStatus.OPEN);
        cb.setPaymentId(null); // No associated payment

        when(chargebackRepository.findByStripeDisputeId(disputeId)).thenReturn(Optional.of(cb));

        String json = String.format(
                "{\"id\":\"evt_close\",\"object\":\"event\",\"type\":\"charge.dispute.closed\"," +
                "\"data\":{\"object\":{\"id\":\"%s\",\"status\":\"won\"}}}", disputeId);
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
        service.handleDisputeClosed(event);

        assertThat(cb.getStatus()).isEqualTo(ChargebackStatus.WON);
        verify(paymentRepository, never()).findById(any());
        verify(chargebackRepository).save(cb);
    }
}
