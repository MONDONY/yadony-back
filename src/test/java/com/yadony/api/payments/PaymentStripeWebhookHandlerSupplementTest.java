package com.yadony.api.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.bankpayout.BankPayoutTrackingService;
import com.yadony.api.payments.cash.CashCommissionWebhookHandler;
import com.yadony.api.payments.chargeback.ChargebackService;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.supplement.BidSupplementCardLedger;
import com.yadony.api.payments.supplement.BidSupplementCardRail;
import com.yadony.api.payments.supplement.BidSupplementEntity;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStripeWebhookRouter;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Branche du supplément EN TÊTE du handler des webhooks de paiement (plan §1.3-d) : une
 * PaymentIntent de supplément n'atteint jamais la commission espèces ni le séquestre du colis ; les
 * recharges du solde et les charges des colis gardent leur traitement.
 */
@ExtendWith(MockitoExtension.class)
class PaymentStripeWebhookHandlerSupplementTest {

    @Mock PaymentService paymentService;
    @Mock CashCommissionWebhookHandler cashHandler;
    @Mock ChargebackService chargebackService;
    @Mock WalletService walletService;
    @Mock WalletSelfRefundService walletSelfRefundService;
    @Mock BankPayoutTrackingService bankPayoutTracking;
    @Mock BidSupplementCardRail cardRail;
    @Mock BidSupplementCardLedger ledger;
    @Mock BidSupplementRepository supplements;
    @Mock AdminAlertEscalator alerts;
    @Mock com.yadony.api.payments.supplement.StripeSupplementGateway gateway;

    private PaymentStripeWebhookHandler handler;
    private final UUID supplementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        BidSupplementStripeWebhookRouter router = new BidSupplementStripeWebhookRouter(cardRail, ledger, supplements,
                alerts, gateway, new ObjectMapper());
        handler = new PaymentStripeWebhookHandler(paymentService, cashHandler, chargebackService, walletService,
                walletSelfRefundService, new ObjectMapper(), new CurrencyCatalog(), bankPayoutTracking, router);
    }

    private static Event event(String type, String objectJson) {
        return ApiResource.GSON.fromJson("{\"id\":\"evt_1\",\"object\":\"event\",\"api_version\":\"" + com.stripe.Stripe.API_VERSION
                + "\",\"type\":\"" + type
                + "\",\"data\":{\"object\":" + objectJson + "}}", Event.class);
    }

    private String supplementPi() {
        return "{\"id\":\"pi_s\",\"object\":\"payment_intent\",\"status\":\"succeeded\",\"latest_charge\":\"ch_s\","
                + "\"metadata\":{\"bid_supplement_id\":\"" + supplementId + "\",\"scope\":\"BID_SUPPLEMENT\"}}";
    }

    @Test
    void paymentIntentDuSupplement_routeur_jamaisLaCommissionEspeces() {
        handler.handle(event("payment_intent.succeeded", supplementPi()));
        handler.handle(event("payment_intent.payment_failed", supplementPi()));
        handler.handle(event("payment_intent.canceled", supplementPi()));

        verify(cardRail).onPaymentSucceeded(supplementId, "pi_s", "ch_s");
        verifyNoInteractions(cashHandler, paymentService, walletService);
    }

    @Test
    void rechargeDuSolde_inchangee() {
        String userId = UUID.randomUUID().toString();
        handler.handle(event("payment_intent.succeeded", "{\"id\":\"pi_w\",\"object\":\"payment_intent\","
                + "\"amount\":1000,\"currency\":\"eur\",\"metadata\":{\"wallet_topup\":\"true\",\"user_id\":\""
                + userId + "\",\"wallet_currency\":\"EUR\"}}"));

        verify(walletService).credit(eq(UUID.fromString(userId)), eq("EUR"), eq(new BigDecimal("10.00")),
                eq(WalletTransactionType.TOP_UP), eq("pi_w"), eq("stripe-pi_w"));
        verifyNoInteractions(cardRail);
    }

    @Test
    void paymentIntentDuColis_commissionEspecesInchangee() {
        handler.handle(event("payment_intent.succeeded", "{\"id\":\"pi_c\",\"object\":\"payment_intent\","
                + "\"metadata\":{\"commission_purpose\":\"cash_bid\",\"bid_id\":\"" + UUID.randomUUID() + "\"}}"));
        verify(cashHandler).handlePaymentIntentSucceeded(any());
        verifyNoInteractions(cardRail);
    }

    @Test
    void chargeRembourseeDuSupplement_routeur_jamaisLeSequestreNiLeSolde() {
        BidSupplementEntity s = new BidSupplementEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(s, "id", supplementId);
        when(supplements.findByStripePaymentIntentId("pi_s")).thenReturn(Optional.of(s));

        handler.handle(event("charge.refunded", "{\"id\":\"ch_s\",\"object\":\"charge\",\"payment_intent\":\"pi_s\","
                + "\"amount\":2110,\"amount_refunded\":2110}"));

        verify(ledger).completeRefund(supplementId, null, "webhook");
        verify(paymentService, never()).handleChargeRefunded(any());
        verifyNoInteractions(walletSelfRefundService);
    }

    @Test
    void litigeSurUneChargeDeColis_chargebackServiceInchange() {
        handler.handle(event("charge.dispute.created", "{\"id\":\"dp_1\",\"object\":\"dispute\",\"charge\":\"ch_c\","
                + "\"payment_intent\":\"pi_c\"}"));
        verify(chargebackService).handleDisputeCreated(any());
    }

    @Test
    void sansRouteur_constructeurHistorique_inchange() {
        PaymentStripeWebhookHandler legacy = new PaymentStripeWebhookHandler(paymentService, cashHandler,
                chargebackService, walletService, walletSelfRefundService, new ObjectMapper(), new CurrencyCatalog(),
                bankPayoutTracking);
        legacy.handle(event("payment_intent.succeeded", supplementPi()));
        verify(cashHandler).handlePaymentIntentSucceeded(any());
    }
}
