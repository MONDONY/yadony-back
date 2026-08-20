package com.yadony.api.common.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.PaymentStripeWebhookHandler;
import com.yadony.api.payments.cash.CashCommissionWebhookHandler;
import com.yadony.api.payments.chargeback.ChargebackService;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeEventProcessorTest {

    @Mock StripeEventInboxRepository repo;
    @Mock StripeEventDispatcher dispatcher;
    @Mock AdminAlertService adminAlert;
    StripeWebhookProperties props =
            new StripeWebhookProperties(Duration.ofSeconds(10), 50, 3, Duration.ofSeconds(5), true);
    StripeEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new StripeEventProcessor(repo, dispatcher, props, adminAlert);
    }

    private StripeEventInbox makeInbox(String id, StripeEventStatus status) {
        var i = new StripeEventInbox(id, StripeWebhookSource.PAYMENTS, "test.event", "{}");
        i.setStatus(status);
        return i;
    }

    @Test
    void processOne_returnsFalse_whenNoEvent() {
        when(repo.claimNext()).thenReturn(Optional.empty());
        assertThat(processor.processOne()).isFalse();
    }

    @Test
    void processOne_setsProcessed_onSuccess() {
        var inbox = makeInbox("evt_1", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch("{}")).thenReturn(true);

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.PROCESSED);
        assertThat(inbox.getProcessedAt()).isNotNull();
    }

    @Test
    void processOne_setsSkipped_whenNoHandler() {
        var inbox = makeInbox("evt_2", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenReturn(false);

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.SKIPPED);
    }

    @Test
    void processOne_setsFailed_withBackoff_onFirstFailure() {
        var inbox = makeInbox("evt_3", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenThrow(new RuntimeException("stripe timeout"));

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.FAILED);
        assertThat(inbox.getRetryCount()).isEqualTo(1);
        assertThat(inbox.getNextAttemptAt()).isAfter(inbox.getReceivedAt());
    }

    @Test
    void processOne_setsDeadLetter_afterMaxRetries() {
        var inbox = makeInbox("evt_4", StripeEventStatus.FAILED);
        inbox.setRetryCount(2); // maxRetries=3, ce sera le 3ème échec
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenThrow(new RuntimeException("persist error"));

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.DEAD_LETTER);
        verify(adminAlert).raise(eq("STRIPE_DEAD_LETTER"), any(), any());
    }

    @Test
    void processOne_paymentIntentResolutionFailure_isNeverProcessedOrCredited() {
        WalletService walletService = mock(WalletService.class);
        CashCommissionWebhookHandler cashHandler = mock(CashCommissionWebhookHandler.class);
        PaymentStripeWebhookHandler paymentHandler = paymentHandler(walletService, cashHandler);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.getRawJson()).thenReturn("{");
        Event event = paymentIntentSucceededEvent(deserializer);

        var inbox = makeInbox("evt_pi_unresolved", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenAnswer(ignored -> {
            paymentHandler.handle(event);
            return true;
        });

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.FAILED);
        assertThat(inbox.getStatus()).isNotEqualTo(StripeEventStatus.PROCESSED);
        assertThat(inbox.getRetryCount()).isEqualTo(1);
        assertThat(inbox.getProcessedAt()).isNull();
        assertThat(inbox.getLastError())
                .isEqualTo("Impossible de résoudre le PaymentIntent Stripe du webhook.");
        verify(cashHandler, never()).handlePaymentIntentSucceeded(any());
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processOne_walletTopupWithoutUserId_movesFromFailedToDeadLetterWithoutCredit() {
        StripeWebhookProperties twoAttempts = new StripeWebhookProperties(
                Duration.ofSeconds(10), 50, 2, Duration.ofSeconds(5), true);
        StripeEventProcessor twoAttemptProcessor =
                new StripeEventProcessor(repo, dispatcher, twoAttempts, adminAlert);
        WalletService walletService = mock(WalletService.class);
        CashCommissionWebhookHandler cashHandler = mock(CashCommissionWebhookHandler.class);
        PaymentStripeWebhookHandler paymentHandler = paymentHandler(walletService, cashHandler);

        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(paymentIntent.getMetadata()).thenReturn(Map.of(
                "wallet_topup", "true",
                "wallet_currency", "cad"));
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));
        Event event = paymentIntentSucceededEvent(deserializer);

        var inbox = makeInbox("evt_wallet_user_missing", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox), Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenAnswer(ignored -> {
            paymentHandler.handle(event);
            return true;
        });

        twoAttemptProcessor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.FAILED);
        assertThat(inbox.getRetryCount()).isEqualTo(1);
        assertThat(inbox.getProcessedAt()).isNull();
        assertThat(inbox.getLastError()).isEqualTo(
                "L'identifiant utilisateur de la recharge wallet est absent ou invalide "
                        + "dans les métadonnées Stripe.");

        twoAttemptProcessor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.DEAD_LETTER);
        assertThat(inbox.getRetryCount()).isEqualTo(2);
        assertThat(inbox.getProcessedAt()).isNotNull();
        verify(adminAlert).raise(eq("STRIPE_DEAD_LETTER"), any(), any());
        verify(cashHandler, never()).handlePaymentIntentSucceeded(any());
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
    }

    private PaymentStripeWebhookHandler paymentHandler(
            WalletService walletService, CashCommissionWebhookHandler cashHandler) {
        return new PaymentStripeWebhookHandler(
                mock(PaymentService.class), cashHandler, mock(ChargebackService.class),
                walletService, mock(WalletSelfRefundService.class), new ObjectMapper(), new CurrencyCatalog());
    }

    private Event paymentIntentSucceededEvent(EventDataObjectDeserializer deserializer) {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }
}
