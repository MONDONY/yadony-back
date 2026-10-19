package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.stripe.model.Capability;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceWebhookHandlerTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlert;
    @Mock StripeGateway stripeGateway;

    PaymentService service;

    @BeforeEach
    void setUp() {
        service = PaymentServiceTestFactory.bare(paymentRepository, userRepository, auditService, adminAlert, stripeGateway);
    }

    /** Build a mock Event backed by a mocked EventDataObjectDeserializer returning the given object. */
    private Event mockEvent(StripeObject obj) {
        Event event = mock(Event.class);
        EventDataObjectDeserializer deser = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deser);
        when(deser.getObject()).thenReturn(Optional.of(obj));
        return event;
    }

    /** Build a mock Event that returns Optional.empty() from getObject() — for raw JSON fallback paths. */
    private Event mockEventNoObject(String rawJson) {
        Event event = mock(Event.class);
        EventDataObjectDeserializer deser = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deser);
        when(deser.getObject()).thenReturn(Optional.empty());
        lenient().when(deser.getRawJson()).thenReturn(rawJson);
        return event;
    }

    // ── handlePaymentIntentCanceled ───────────────────────────────────────────

    @Test
    void handlePaymentIntentCanceled_cancelsEscrowPayment() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_123");

        var payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.of(payment));

        service.handlePaymentIntentCanceled(mockEvent(pi));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_INTENT_CANCELED"), any(), any());
    }

    @Test
    void handlePaymentIntentCanceled_cancelsPendingPayment() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_pending");

        var payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_pending")).thenReturn(Optional.of(payment));

        service.handlePaymentIntentCanceled(mockEvent(pi));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentRepository).save(payment);
    }

    @Test
    void handlePaymentIntentCanceled_doesNotCancelReleasedPayment() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_456");

        var payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.RELEASED);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_456")).thenReturn(Optional.of(payment));

        service.handlePaymentIntentCanceled(mockEvent(pi));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(paymentRepository, never()).save(any());
    }

    // ── handleAccountDeauthorized ─────────────────────────────────────────────
    // The handler has a typed path (getObject → Account) with raw JSON fallback.
    // In unit tests the Stripe SDK cannot fully deserialize typed objects (no API version context),
    // so the typed getObject() check produces a null accountId and the code falls through to the
    // raw JSON path. We test via mockEventNoObject (empty Optional) to exercise the fallback path,
    // which is the path that actually runs in test context.

    @Test
    void handleAccountDeauthorized_setsUserDisabled() {
        var user = new UserEntity();
        PaymentServiceTestFactory.setId(user, java.util.UUID.randomUUID());
        when(userRepository.findByStripeAccountId("acct_test_001")).thenReturn(Optional.of(user));

        service.handleAccountDeauthorized(
                mockEventNoObject("{\"id\":\"acct_test_001\",\"object\":\"account\"}"));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.DISABLED);
        verify(userRepository).save(user);
        verify(adminAlert).raise(eq("STRIPE_ACCOUNT_DEAUTHORIZED"), any(), any());
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_ACCOUNT_DEAUTHORIZED"), any(), any());
    }

    @Test
    void handleAccountDeauthorized_logsWarnWhenUserNotFound() {
        service.handleAccountDeauthorized(
                mockEventNoObject("{\"id\":\"acct_unknown\",\"object\":\"account\"}"));

        verify(userRepository, never()).save(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    // ── handleCapabilityUpdated ───────────────────────────────────────────────

    /** Évènement capability.updated avec les attributs précédents donnés (null = aucun). */
    private Event capabilityEvent(Capability cap, java.util.Map<String, Object> previousAttributes) {
        Event event = mockEvent(cap);
        Event.Data data = new Event.Data();
        data.setPreviousAttributes(previousAttributes);
        lenient().when(event.getData()).thenReturn(data);
        return event;
    }

    private Capability inactiveCapability(String id, String accountId) {
        Capability cap = mock(Capability.class);
        lenient().when(cap.getId()).thenReturn(id);
        when(cap.getStatus()).thenReturn("inactive");
        when(cap.getAccount()).thenReturn(accountId);
        return cap;
    }

    @Test
    void handleCapabilityUpdated_inactiveAfterUnrequested_isOnboarding_noAlertNoDisable() {
        Capability cap = inactiveCapability("transfers", "acct_cap_new");

        service.handleCapabilityUpdated(capabilityEvent(cap, java.util.Map.of("status", "unrequested")));

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(userRepository, never()).save(any());
        verify(adminAlert, never()).raise(any(), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any(), any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void handleCapabilityUpdated_inactiveWithoutPreviousStatus_isIgnored() {
        Capability cap = inactiveCapability("card_payments", "acct_cap_req");

        // Statut inchangé : Stripe ne met que les requirements dans previous_attributes.
        service.handleCapabilityUpdated(capabilityEvent(cap,
                java.util.Map.of("requirements", java.util.Map.of("currently_due", java.util.List.of()))));

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(adminAlert, never()).raise(any(), any(), any(), any());
    }

    @Test
    void handleCapabilityUpdated_inactiveWithoutAnyPreviousAttributes_isIgnored() {
        Capability cap = inactiveCapability("transfers", "acct_cap_none");

        service.handleCapabilityUpdated(capabilityEvent(cap, null));

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(adminAlert, never()).raise(any(), any(), any(), any());
    }

    @Test
    void handleCapabilityUpdated_inactiveWithoutEventData_isIgnored() {
        Capability cap = inactiveCapability("transfers", "acct_cap_nodata");
        Event event = mockEvent(cap);
        when(event.getData()).thenReturn(null);

        service.handleCapabilityUpdated(event);

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(adminAlert, never()).raise(any(), any(), any(), any());
    }

    @Test
    void handleCapabilityUpdated_activeToInactive_alertsAuditsAndDerivesStatusFromAccount() throws Exception {
        Capability cap = inactiveCapability("transfers", "acct_cap_001");
        Capability.Requirements req = mock(Capability.Requirements.class);
        when(req.getDisabledReason()).thenReturn("requirements.past_due");
        when(req.getCurrentlyDue()).thenReturn(java.util.List.of("individual.verification.document"));
        when(cap.getRequirements()).thenReturn(req);

        var user = new UserEntity();
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findByStripeAccountId("acct_cap_001")).thenReturn(Optional.of(user));

        com.stripe.model.Account account = mock(com.stripe.model.Account.class);
        com.stripe.model.Account.Requirements accReq = mock(com.stripe.model.Account.Requirements.class);
        when(accReq.getDisabledReason()).thenReturn("requirements.past_due");
        when(account.getRequirements()).thenReturn(accReq);
        when(account.getChargesEnabled()).thenReturn(false);
        when(stripeGateway.retrieveAccount("acct_cap_001")).thenReturn(account);

        service.handleCapabilityUpdated(capabilityEvent(cap, java.util.Map.of("status", "active")));

        // Pièce à fournir : l'utilisateur peut reprendre l'onboarding, pas un DISABLED sans issue.
        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
        verify(userRepository).save(user);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> ctx =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(adminAlert, times(1)).raise(eq("STRIPE_CAPABILITY_LOST"), any(), ctx.capture(), eq("acct_cap_001"));
        assertThat(ctx.getValue())
                .containsEntry("capability", "transfers")
                .containsEntry("previousStatus", "active")
                .containsEntry("disabledReason", "requirements.past_due")
                .containsEntry("currentlyDue", "individual.verification.document")
                .containsEntry("stripeAccountStatus", "PENDING_ONBOARDING");
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_CAPABILITY_LOST"), any(), any());
    }

    @Test
    void handleCapabilityUpdated_activeToInactive_accountRejected_derivesRejected() throws Exception {
        Capability cap = inactiveCapability("card_payments", "acct_cap_004");
        when(cap.getRequirements()).thenReturn(null);

        var user = new UserEntity();
        when(userRepository.findByStripeAccountId("acct_cap_004")).thenReturn(Optional.of(user));
        com.stripe.model.Account account = mock(com.stripe.model.Account.class);
        com.stripe.model.Account.Requirements accReq = mock(com.stripe.model.Account.Requirements.class);
        when(accReq.getDisabledReason()).thenReturn("rejected.fraud");
        when(account.getRequirements()).thenReturn(accReq);
        when(stripeGateway.retrieveAccount("acct_cap_004")).thenReturn(account);

        service.handleCapabilityUpdated(capabilityEvent(cap, java.util.Map.of("status", "active")));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.REJECTED);
        verify(adminAlert).raise(eq("STRIPE_CAPABILITY_LOST"), any(), any(), eq("acct_cap_004"));
    }

    @Test
    void handleCapabilityUpdated_activeToInactive_accountFetchFails_fallsBackToDisabled() throws Exception {
        Capability cap = inactiveCapability("transfers", "acct_cap_005");
        Capability.Requirements req = mock(Capability.Requirements.class);
        when(req.getDisabledReason()).thenReturn(null);
        when(req.getCurrentlyDue()).thenReturn(java.util.List.of());
        when(cap.getRequirements()).thenReturn(req);

        var user = new UserEntity();
        when(userRepository.findByStripeAccountId("acct_cap_005")).thenReturn(Optional.of(user));
        when(stripeGateway.retrieveAccount("acct_cap_005"))
                .thenThrow(new com.stripe.exception.ApiConnectionException("timeout"));

        service.handleCapabilityUpdated(capabilityEvent(cap, java.util.Map.of("status", "active")));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.DISABLED);
        verify(userRepository).save(user);
        verify(adminAlert).raise(eq("STRIPE_CAPABILITY_LOST"), any(), any(), eq("acct_cap_005"));
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_CAPABILITY_LOST"), any(), any());
    }

    @Test
    void handleCapabilityUpdated_activeToInactive_unknownAccount_doesNothing() {
        Capability cap = inactiveCapability("transfers", "acct_cap_ghost");
        when(userRepository.findByStripeAccountId("acct_cap_ghost")).thenReturn(Optional.empty());

        service.handleCapabilityUpdated(capabilityEvent(cap, java.util.Map.of("status", "active")));

        verify(userRepository, never()).save(any());
        verify(adminAlert, never()).raise(any(), any(), any(), any());
    }

    @Test
    void handleCapabilityUpdated_doesNotDisableOnPending() {
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn("transfers");
        when(cap.getStatus()).thenReturn("pending");
        when(cap.getAccount()).thenReturn("acct_cap_002");

        service.handleCapabilityUpdated(mockEvent(cap));

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void handleCapabilityUpdated_doesNotDisableOnActive() {
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn("card_payments");
        when(cap.getStatus()).thenReturn("active");
        when(cap.getAccount()).thenReturn("acct_cap_003");

        service.handleCapabilityUpdated(mockEvent(cap));

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    // ── handleChargeRefunded ──────────────────────────────────────────────────
    // Stripe charge.amount_refunded est ABSOLU et CUMULÉ (en cents). Le handler est
    // une machine à états sur les montants absolus : enregistre toujours refundedAmount,
    // ne réécrit jamais un paiement RELEASED, trace les refunds partiels, idempotent au replay.

    /** Build a mock Charge with the given paymentIntent, total amount and cumulative amount_refunded (cents). */
    private Charge mockCharge(String piId, long amount, long amountRefunded) {
        Charge charge = mock(Charge.class);
        when(charge.getPaymentIntent()).thenReturn(piId);
        lenient().when(charge.getAmount()).thenReturn(amount);
        lenient().when(charge.getAmountRefunded()).thenReturn(amountRefunded);
        return charge;
    }

    @Test
    void handleChargeRefunded_fullRefundOnEscrow_setsRefundedAndStatus() {
        Charge charge = mockCharge("pi_refund_full", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_full")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED"), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void handleChargeRefunded_partialRefundOnEscrow_keepsStatusRecordsAmount() {
        Charge charge = mockCharge("pi_refund_partial", 3000L, 1000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_partial")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_PARTIALLY_REFUNDED"), any(), any());
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_REFUNDED"), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void handleChargeRefunded_onReleased_keepsStatusRaisesAdminAlert() {
        Charge charge = mockCharge("pi_refund_released", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.RELEASED);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_released")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(paymentRepository).save(payment);
        verify(adminAlert).raise(eq("REFUND_AFTER_RELEASE"), any(), any());
        verify(auditService).log(eq("PAYMENT"), any(), eq("REFUND_AFTER_RELEASE"), any(), any());
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void handleChargeRefunded_replaySameWebhook_idempotentNoDuplicateAlert() {
        Charge charge = mockCharge("pi_refund_replay", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.RELEASED);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_replay")).thenReturn(Optional.of(payment));

        // First delivery → records amount + raises alert once.
        service.handleChargeRefunded(mockEvent(charge));
        // Replay identical webhook → alreadyRecorded → no duplicate alert/audit.
        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(adminAlert, times(1)).raise(eq("REFUND_AFTER_RELEASE"), any(), any());
        verify(auditService, times(1)).log(eq("PAYMENT"), any(), eq("REFUND_AFTER_RELEASE"), any(), any());
    }

    @Test
    void handleChargeRefunded_alreadyRefunded_updatesAmountNoStatusChange() {
        Charge charge = mockCharge("pi_refund_already", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.REFUNDED);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_already")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(paymentRepository).save(payment);
        // Déjà REFUNDED : pas de nouvel audit PAYMENT_REFUNDED, pas d'alerte.
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_REFUNDED"), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }
}
