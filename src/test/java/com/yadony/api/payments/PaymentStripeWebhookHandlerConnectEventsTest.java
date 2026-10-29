package com.yadony.api.payments;

import com.yadony.api.payments.chargeback.ChargebackService;
import com.yadony.api.payments.cash.CashCommissionWebhookHandler;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentStripeWebhookHandlerConnectEventsTest {

    @Mock PaymentService paymentService;
    @Mock CashCommissionWebhookHandler cashHandler;
    @Mock ChargebackService chargebackService;
    @Mock WalletService walletService;
    @Mock WalletSelfRefundService walletSelfRefundService;
    PaymentStripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentStripeWebhookHandler(paymentService, cashHandler, chargebackService, walletService,
                walletSelfRefundService, new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.yadony.api.payments.currency.CurrencyCatalog());
    }

    private Event evt(String type) {
        return ApiResource.GSON.fromJson(
            "{\"id\":\"e\",\"object\":\"event\",\"type\":\"" + type + "\",\"data\":{\"object\":{}}}",
            Event.class);
    }

    @Test void handles_deauthorized()  { handler.handle(evt("account.application.deauthorized")); verify(paymentService).handleAccountDeauthorized(any()); }
    @Test void handles_capability()    { handler.handle(evt("capability.updated")); verify(paymentService).handleCapabilityUpdated(any()); }
    @Test void handles_refundUpdated() { handler.handle(evt("charge.refund.updated")); verify(paymentService).handleRefundUpdated(any()); }
    @Test void handles_fraudWarning()  { handler.handle(evt("radar.early_fraud_warning.created")); verify(paymentService).handleEarlyFraudWarning(any()); }

    private Event connectEvt(String type, String dataObject) {
        return ApiResource.GSON.fromJson(
            "{\"id\":\"e\",\"object\":\"event\",\"account\":\"acct_traveler\",\"type\":\"" + type
                    + "\",\"data\":{\"object\":" + dataObject + "}}",
            Event.class);
    }

    @Test
    void connectedAccount_walletTopupPaymentIntent_neverCredits() {
        handler.handle(connectEvt("payment_intent.succeeded",
                "{\"id\":\"pi_x\",\"object\":\"payment_intent\",\"amount\":50000,\"currency\":\"eur\","
                        + "\"metadata\":{\"wallet_topup\":\"true\",\"user_id\":\""
                        + java.util.UUID.randomUUID() + "\",\"wallet_currency\":\"EUR\"}}"));
        org.mockito.Mockito.verifyNoInteractions(walletService, cashHandler, paymentService, chargebackService,
                walletSelfRefundService);
    }

    @Test
    void connectedAccount_disputeOrRefund_ignored() {
        handler.handle(connectEvt("charge.dispute.created", "{}"));
        handler.handle(connectEvt("charge.refunded", "{}"));
        org.mockito.Mockito.verifyNoInteractions(chargebackService, paymentService, walletSelfRefundService);
    }

    @Test
    void connectedAccount_accountLifecycle_stillHandled() {
        handler.handle(connectEvt("capability.updated", "{}"));
        handler.handle(connectEvt("account.updated", "{}"));
        verify(paymentService).handleCapabilityUpdated(any());
        verify(paymentService).handleAccountUpdated(any());
    }

    @Test
    void supports_allNewConnectEvents() {
        assertThat(handler.supports("account.application.deauthorized")).isTrue();
        assertThat(handler.supports("radar.early_fraud_warning.created")).isTrue();
        assertThat(handler.supports("unknown.event")).isFalse();
    }
}
