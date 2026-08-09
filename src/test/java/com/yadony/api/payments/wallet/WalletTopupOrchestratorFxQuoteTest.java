package com.yadony.api.payments.wallet;

import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.currency.StripeFxQuoteService;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Régression : PaymentIntent.create doit recevoir le header Stripe-Version preview
 * quand un fx_quote est attaché aux params, sinon Stripe rejette avec
 * "Received unknown parameter: fx_quote".
 */
class WalletTopupOrchestratorFxQuoteTest {

    private WalletTopupOrchestrator buildOrchestrator(StripeFxQuoteService fxQuoteService) throws Exception {
        WalletTopupOrchestrator orchestrator = new WalletTopupOrchestrator();
        setField(orchestrator, "fxQuotesApiVersion", "2025-03-31.preview");
        orchestrator.configureCurrency(new CurrencyCatalog(),
                new com.yadony.api.payments.currency.FxRateService(
                        (source, target) -> new BigDecimal("1.10"),
                        new com.yadony.api.payments.currency.ExchangeRateProperties(
                                new BigDecimal("655.957"), new BigDecimal("655.957"), 300),
                        com.github.benmanes.caffeine.cache.Caffeine.newBuilder().build()));
        orchestrator.configureFxQuotes(fxQuoteService);
        return orchestrator;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = WalletTopupOrchestrator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void includesStripeVersionOverrideWhenFxQuoteIsUsed() throws Exception {
        StripeFxQuoteService fxQuoteService = mock(StripeFxQuoteService.class);
        StripeFxQuoteService.FxQuoteSnapshot snapshot = new StripeFxQuoteService.FxQuoteSnapshot(
                "fxq_test", SupportedCurrency.USD, new BigDecimal("0.90"),
                new BigDecimal("1.11111"), Instant.now().plusSeconds(3600));
        when(fxQuoteService.createPaymentQuote(SupportedCurrency.USD)).thenReturn(snapshot);

        WalletTopupOrchestrator orchestrator = buildOrchestrator(fxQuoteService);

        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal("20.00"));
        request.setPaymentMethod("STRIPE");
        request.setCurrencyCode("USD");

        try (MockedStatic<PaymentIntent> mockedPi = mockStatic(PaymentIntent.class)) {
            PaymentIntent fakePi = mock(PaymentIntent.class);
            when(fakePi.getClientSecret()).thenReturn("secret_test");
            mockedPi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(fakePi);

            orchestrator.initiate(UUID.randomUUID(), request);

            mockedPi.verify(() -> PaymentIntent.create(
                    any(PaymentIntentCreateParams.class),
                    org.mockito.ArgumentMatchers.argThat((RequestOptions options) ->
                            "2025-03-31.preview".equals(RequestOptions.unsafeGetStripeVersionOverride(options)))));
        }
    }

    @Test
    void doesNotOverrideStripeVersionWhenNoFxQuote() throws Exception {
        WalletTopupOrchestrator orchestrator = buildOrchestrator(null);

        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal("20.00"));
        request.setPaymentMethod("STRIPE");
        request.setCurrencyCode("EUR");

        try (MockedStatic<PaymentIntent> mockedPi = mockStatic(PaymentIntent.class)) {
            PaymentIntent fakePi = mock(PaymentIntent.class);
            when(fakePi.getClientSecret()).thenReturn("secret_test");
            mockedPi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(fakePi);

            orchestrator.initiate(UUID.randomUUID(), request);

            mockedPi.verify(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)));
        }
    }
}
