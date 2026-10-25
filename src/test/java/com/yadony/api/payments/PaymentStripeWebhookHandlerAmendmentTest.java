package com.yadony.api.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import com.yadony.api.payments.amendment.BidAmendmentStripeWebhookRouter;
import com.yadony.api.payments.bankpayout.BankPayoutTrackingService;
import com.yadony.api.payments.cash.CashCommissionWebhookHandler;
import com.yadony.api.payments.chargeback.ChargebackService;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.supplement.BidSupplementStripeWebhookRouter;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.yadony.api.payments.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Branche des modifications de colis en tête du handler des webhooks de paiement, juste APRÈS celle du supplément :
 * la commission d'une modification n'atteint jamais le séquestre du colis ni la commission espèces du colis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentStripeWebhookHandler : routeur des modifications de colis")
class PaymentStripeWebhookHandlerAmendmentTest {

    @Mock PaymentService paymentService;
    @Mock CashCommissionWebhookHandler cashHandler;
    @Mock ChargebackService chargebackService;
    @Mock WalletService walletService;
    @Mock WalletSelfRefundService walletSelfRefundService;
    @Mock BankPayoutTrackingService bankPayoutTracking;
    @Mock BidSupplementStripeWebhookRouter supplementRouter;
    @Mock BidAmendmentStripeWebhookRouter amendmentRouter;

    private PaymentStripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentStripeWebhookHandler(paymentService, cashHandler, chargebackService, walletService,
                walletSelfRefundService, new ObjectMapper(), new CurrencyCatalog(), bankPayoutTracking,
                supplementRouter, amendmentRouter);
    }

    private static Event event(String type, String objectJson) {
        return ApiResource.GSON.fromJson("{\"id\":\"evt_1\",\"object\":\"event\",\"api_version\":\""
                + com.stripe.Stripe.API_VERSION + "\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson
                + "}}", Event.class);
    }

    private static Event commissionEvent() {
        return event("payment_intent.succeeded", "{\"id\":\"pi_c\",\"object\":\"payment_intent\",\"metadata\":{"
                + "\"bid_amendment_id\":\"" + UUID.randomUUID()
                + "\",\"commission_purpose\":\"bid_amendment_commission\"}}");
    }

    @Test
    @DisplayName("commission d'une modification : traitée par son routeur, jamais le colis ni la commission espèces")
    void amendmentEvent_handledByItsRouterOnly() {
        Event event = commissionEvent();
        when(amendmentRouter.handle(event)).thenReturn(true);

        handler.handle(event);

        var order = inOrder(supplementRouter, amendmentRouter);
        order.verify(supplementRouter).handle(event);
        order.verify(amendmentRouter).handle(event);
        verifyNoInteractions(cashHandler, paymentService, walletService, chargebackService);
    }

    @Test
    @DisplayName("événement du supplément : le routeur des modifications n'est pas consulté")
    void supplementEvent_neverReachesAmendmentRouter() {
        Event event = commissionEvent();
        when(supplementRouter.handle(event)).thenReturn(true);

        handler.handle(event);

        verifyNoInteractions(amendmentRouter, cashHandler, paymentService);
    }

    @Test
    @DisplayName("événement d'aucune modification : le traitement historique du colis est inchangé")
    void otherEvent_followsHistoricalPath() {
        Event event = event("payment_intent.succeeded", "{\"id\":\"pi_c\",\"object\":\"payment_intent\","
                + "\"metadata\":{\"commission_purpose\":\"cash_bid\",\"bid_id\":\"" + UUID.randomUUID() + "\"}}");
        when(amendmentRouter.handle(event)).thenReturn(false);

        handler.handle(event);

        verify(cashHandler).handlePaymentIntentSucceeded(any());
    }

    @Test
    @DisplayName("constructeurs historiques, sans routeur de modification : inchangés")
    void legacyConstructors_withoutAmendmentRouter() {
        PaymentStripeWebhookHandler legacy = new PaymentStripeWebhookHandler(paymentService, cashHandler,
                chargebackService, walletService, walletSelfRefundService, new ObjectMapper(), new CurrencyCatalog(),
                bankPayoutTracking, supplementRouter);

        legacy.handle(commissionEvent());

        verify(cashHandler).handlePaymentIntentSucceeded(any());
    }
}
