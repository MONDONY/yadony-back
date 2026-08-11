package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.cash.CashCommissionWebhookHandler;
import com.yadony.api.payments.chargeback.ChargebackService;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.ApiResource;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentStripeWebhookHandlerTest {

    private static final String TEST_USER_ID = "00000000-0000-0000-0000-000000000042";

    @Mock PaymentService paymentService;
    @Mock CashCommissionWebhookHandler cashHandler;
    @Mock ChargebackService chargebackService;
    @Mock WalletService walletService;
    PaymentStripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentStripeWebhookHandler(paymentService, cashHandler, chargebackService,
                walletService, new ObjectMapper(), new CurrencyCatalog());
    }

    private Event buildEvent(String type) {
        String json = String.format(
            "{\"id\":\"evt_x\",\"object\":\"event\",\"type\":\"%s\"," +
            "\"data\":{\"object\":{}}}", type);
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    @Test
    void supports_trueForPaymentEvents() {
        assertThat(handler.supports("account.updated")).isTrue();
        assertThat(handler.supports("payment_intent.amount_capturable_updated")).isTrue();
        assertThat(handler.supports("payment_intent.payment_failed")).isTrue();
        assertThat(handler.supports("charge.refunded")).isTrue();
        assertThat(handler.supports("setup_intent.succeeded")).isTrue();
        assertThat(handler.supports("payment_intent.succeeded")).isTrue();
        assertThat(handler.supports("payment_method.detached")).isTrue();
        assertThat(handler.supports("charge.dispute.created")).isTrue();
        assertThat(handler.supports("charge.dispute.closed")).isTrue();
        assertThat(handler.supports("charge.dispute.funds_withdrawn")).isTrue();
        assertThat(handler.supports("charge.dispute.funds_reinstated")).isTrue();
        assertThat(handler.supports("identity.verification_session.verified")).isFalse();
        assertThat(handler.supports("unknown.event")).isFalse();
    }

    @Test
    void handle_accountUpdated_callsService() {
        handler.handle(buildEvent("account.updated"));
        verify(paymentService).handleAccountUpdated(any());
    }

    @Test
    void handle_paymentEscrowActive_callsService() {
        handler.handle(buildEvent("payment_intent.amount_capturable_updated"));
        verify(paymentService).handlePaymentEscrowActive(any());
    }

    @Test
    void handle_paymentFailed_callsBothHandlers() {
        handler.handle(buildEvent("payment_intent.payment_failed"));
        verify(paymentService).handlePaymentFailed(any());
        verify(cashHandler).handlePaymentIntentFailed(any());
    }

    @Test
    void handle_chargeRefunded_callsService() {
        handler.handle(buildEvent("charge.refunded"));
        verify(paymentService).handleChargeRefunded(any());
    }

    @Test
    void handle_setupIntentSucceeded_callsCashHandler() {
        handler.handle(buildEvent("setup_intent.succeeded"));
        verify(cashHandler).handleSetupIntentSucceeded(any());
    }

    @Test
    void handle_paymentMethodDetached_callsCashHandler() {
        handler.handle(buildEvent("payment_method.detached"));
        verify(cashHandler).handlePaymentMethodDetached(any());
    }

    @Test
    void handle_disputeCreated_callsChargebackService() {
        handler.handle(buildEvent("charge.dispute.created"));
        verify(chargebackService).handleDisputeCreated(any());
    }

    @Test
    void handle_disputeClosed_callsChargebackService() {
        handler.handle(buildEvent("charge.dispute.closed"));
        verify(chargebackService).handleDisputeClosed(any());
    }

    @Test
    void handle_disputeFundsWithdrawn_callsChargebackService() {
        handler.handle(buildEvent("charge.dispute.funds_withdrawn"));
        verify(chargebackService).handleFundsWithdrawn(any());
    }

    @Test
    void handle_disputeFundsReinstated_callsChargebackService() {
        handler.handle(buildEvent("charge.dispute.funds_reinstated"));
        verify(chargebackService).handleFundsReinstated(any());
    }

    @Test
    void handle_paymentIntentSucceeded_walletTopup_creditsMetadataCurrencyOneToOne() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test");
        when(pi.getAmount()).thenReturn(5000L);
        when(pi.getCurrency()).thenReturn("cad");
        when(pi.getMetadata()).thenReturn(Map.of(
                "wallet_topup", "true",
                "user_id", TEST_USER_ID,
                "wallet_currency", "cad",
                "wallet_credit_eur", "33.33"));

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(pi));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        handler.handle(event);

        verify(walletService).credit(
            eq(UUID.fromString(TEST_USER_ID)),
            eq("CAD"),
            eq(new BigDecimal("50.00")),
            eq(WalletTransactionType.TOP_UP),
            eq("pi_test"),
            eq("stripe-pi_test")
        );
        verify(cashHandler, never()).handlePaymentIntentSucceeded(any());
    }

    @Test
    void handle_paymentIntentSucceeded_walletTopup_usesCurrencyMinorUnits() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_xof");
        when(pi.getAmount()).thenReturn(5000L);
        when(pi.getCurrency()).thenReturn("xof");
        when(pi.getMetadata()).thenReturn(Map.of(
                "wallet_topup", "true",
                "user_id", TEST_USER_ID,
                "wallet_currency", "xof"));

        handler.handle(eventWith(pi));

        verify(walletService).credit(
                eq(UUID.fromString(TEST_USER_ID)),
                eq("XOF"),
                eq(new BigDecimal("5000")),
                eq(WalletTransactionType.TOP_UP),
                eq("pi_xof"),
                eq("stripe-pi_xof"));
    }

    @Test
    void handle_paymentIntentSucceeded_walletTopup_rejectsUnsupportedMetadataCurrency() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getMetadata()).thenReturn(Map.of(
                "wallet_topup", "true",
                "user_id", TEST_USER_ID,
                "wallet_currency", "btc"));

        assertThatThrownBy(() -> handler.handle(eventWith(pi)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("unsupported-currency"));
        verify(walletService, never()).credit(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void handle_paymentIntentSucceeded_walletTopup_rejectsMissingMetadataCurrency() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getMetadata()).thenReturn(Map.of(
                "wallet_topup", "true",
                "user_id", TEST_USER_ID));

        assertThatThrownBy(() -> handler.handle(eventWith(pi)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("wallet-topup-currency-missing"));
        verify(walletService, never()).credit(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void handle_paymentIntentSucceeded_walletTopup_rejectsChargeAndMetadataCurrencyMismatch() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getCurrency()).thenReturn("usd");
        when(pi.getMetadata()).thenReturn(Map.of(
                "wallet_topup", "true",
                "user_id", TEST_USER_ID,
                "wallet_currency", "cad"));

        assertThatThrownBy(() -> handler.handle(eventWith(pi)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("wallet-topup-currency-mismatch"));
        verify(walletService, never()).credit(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void handle_paymentIntentSucceeded_walletTopup_deserializerEmpty_fetchesViaApiAndCredits() {
        // Régression : mismatch de version d'API Stripe/SDK → getObject() vide.
        // Sans le fallback (getRawJson + PaymentIntent.retrieve), le wallet n'était
        // jamais crédité car le routage tombait dans la branche bid (cashHandler).
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.getRawJson()).thenReturn("{\"id\":\"pi_fallback\"}");

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        PaymentIntent fetched = mock(PaymentIntent.class);
        when(fetched.getId()).thenReturn("pi_fallback");
        when(fetched.getAmount()).thenReturn(10000L);
        when(fetched.getCurrency()).thenReturn("eur");
        when(fetched.getMetadata()).thenReturn(Map.of(
                "wallet_topup", "true",
                "user_id", TEST_USER_ID,
                "wallet_currency", "eur"));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve("pi_fallback")).thenReturn(fetched);
            handler.handle(event);
        }

        verify(walletService).credit(
            eq(UUID.fromString(TEST_USER_ID)),
            eq("EUR"),
            eq(new BigDecimal("100.00")),
            eq(WalletTransactionType.TOP_UP),
            eq("pi_fallback"),
            eq("stripe-pi_fallback")
        );
        verify(cashHandler, never()).handlePaymentIntentSucceeded(any());
    }

    @Test
    void handle_paymentIntentSucceeded_notWalletTopup_callsCashHandler() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getMetadata()).thenReturn(Map.of());

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(pi));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        handler.handle(event);

        verify(cashHandler).handlePaymentIntentSucceeded(any());
        verify(walletService, never()).credit(any(), anyString(), any(), any(), any(), any());
    }

    private Event eventWith(PaymentIntent paymentIntent) {
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }
}
