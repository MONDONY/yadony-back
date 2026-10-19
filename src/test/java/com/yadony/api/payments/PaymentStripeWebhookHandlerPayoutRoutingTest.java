package com.yadony.api.payments;

import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import com.yadony.api.payments.bankpayout.BankPayoutTrackingService;
import com.yadony.api.payments.cash.CashCommissionWebhookHandler;
import com.yadony.api.payments.chargeback.ChargebackService;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.yadony.api.payments.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentStripeWebhookHandlerPayoutRoutingTest {

    @Mock PaymentService paymentService;
    @Mock CashCommissionWebhookHandler cashHandler;
    @Mock ChargebackService chargebackService;
    @Mock WalletService walletService;
    @Mock WalletSelfRefundService walletSelfRefundService;
    @Mock BankPayoutTrackingService tracking;
    PaymentStripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentStripeWebhookHandler(paymentService, cashHandler, chargebackService, walletService,
                walletSelfRefundService, new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.yadony.api.payments.currency.CurrencyCatalog(), tracking);
    }

    private static Event evt(String type, String account) {
        String acc = account == null ? "" : ",\"account\":\"" + account + "\"";
        return ApiResource.GSON.fromJson("{\"id\":\"e\",\"object\":\"event\",\"type\":\"" + type + "\"" + acc
                + ",\"data\":{\"object\":{\"id\":\"po_1\",\"object\":\"payout\"}}}", Event.class);
    }

    @Test
    void supportsEveryPayoutEvent() {
        for (String type : new String[]{"payout.created", "payout.updated", "payout.paid", "payout.failed",
                "payout.canceled"}) {
            assertThat(handler.supports(type)).as(type).isTrue();
        }
    }

    @Test
    void connectedAccountPayouts_goToBankTracking() {
        for (String type : new String[]{"payout.created", "payout.updated", "payout.paid", "payout.failed",
                "payout.canceled"}) {
            handler.handle(evt(type, "acct_1"));
        }
        verify(tracking, org.mockito.Mockito.times(5)).handlePayoutEvent(any());
        verifyNoInteractions(paymentService);
    }

    @Test
    void platformPayouts_keepHistoricalHandling() {
        handler.handle(evt("payout.paid", null));
        handler.handle(evt("payout.failed", null));
        handler.handle(evt("payout.created", null));
        verify(paymentService).handlePayoutPaid(any());
        verify(paymentService).handlePayoutFailed(any());
        verify(tracking, never()).handlePayoutEvent(any());
    }
}
