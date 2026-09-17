package com.yadony.api.payments.wallet.fees;

import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentRetrieveParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeFeeSourceTest {

    private final StripeFeeSource source = new StripeFeeSource(new BigDecimal("3.15"), new BigDecimal("0.25"));

    private PaymentIntent stubPaymentIntent(long feeCents, String balanceTransactionCurrency) {
        BalanceTransaction balanceTransaction = mock(BalanceTransaction.class);
        when(balanceTransaction.getFee()).thenReturn(feeCents);
        when(balanceTransaction.getCurrency()).thenReturn(balanceTransactionCurrency);
        Charge charge = mock(Charge.class);
        when(charge.getBalanceTransactionObject()).thenReturn(balanceTransaction);
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getLatestChargeObject()).thenReturn(charge);
        return pi;
    }

    @Test
    void readsFeeFromBalanceTransaction() throws StripeException {
        PaymentIntent pi = stubPaymentIntent(151L, "eur");
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(eq("pi_1"), any(PaymentIntentRetrieveParams.class), any()))
                    .thenReturn(pi);

            assertThat(source.fee("pi_1", "EUR")).isEqualByComparingTo("1.51");
        }
    }

    @Test
    void stripeDown_fallsBackToConfiguredRate() throws StripeException {
        StripeException stripeException = mock(StripeException.class);
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(eq("pi_1"), any(PaymentIntentRetrieveParams.class), any()))
                    .thenThrow(stripeException);

            assertThat(source.fee("pi_1", "EUR")).isNull();
        }
        // 40 x 3.15 % + 0.25 = 1.51, indépendamment de l'appel Stripe ci-dessus.
        assertThat(source.fallback(new BigDecimal("40.00"), "EUR")).isEqualByComparingTo("1.51");
    }

    @Test
    void secondCallHitsCache() throws StripeException {
        PaymentIntent pi = stubPaymentIntent(151L, "eur");
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(eq("pi_1"), any(PaymentIntentRetrieveParams.class), any()))
                    .thenReturn(pi);

            assertThat(source.fee("pi_1", "EUR")).isEqualByComparingTo("1.51");
            assertThat(source.fee("pi_1", "EUR")).isEqualByComparingTo("1.51");

            piStatic.verify(() -> PaymentIntent.retrieve(eq("pi_1"), any(PaymentIntentRetrieveParams.class), any()),
                    times(1));
        }
    }

    @Test
    void balanceTransactionCurrencyMismatch_fallsBackWithoutCaching() throws StripeException {
        // Le paiement d'origine était en USD (ex. carte étrangère) alors que le wallet
        // rembourse en EUR : lire ce frais serait faux, on force le repli.
        PaymentIntent pi = stubPaymentIntent(151L, "usd");
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(eq("pi_1"), any(PaymentIntentRetrieveParams.class), any()))
                    .thenReturn(pi);

            assertThat(source.fee("pi_1", "EUR")).isNull();

            // Rien n'a été mis en cache : un second appel retente Stripe au lieu de
            // renvoyer un résultat figé.
            assertThat(source.fee("pi_1", "EUR")).isNull();
            piStatic.verify(() -> PaymentIntent.retrieve(eq("pi_1"), any(PaymentIntentRetrieveParams.class), any()),
                    times(2));
        }
    }
}
