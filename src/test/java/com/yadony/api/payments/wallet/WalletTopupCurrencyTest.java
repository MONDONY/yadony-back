package com.yadony.api.payments.wallet;

import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyCatalog;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * La devise créditée est celle que le SERVEUR reconnaît à l'utilisateur, jamais
 * celle envoyée par le client — c'est la même que celle relue par les contrôles
 * de solde ({@code ActiveCurrencyResolver}). Laisser le client la choisir
 * permettait de créditer une devise et d'en contrôler une autre : le voyageur
 * rechargeait, son solde restait « insuffisant », sans issue possible.
 */
class WalletTopupCurrencyTest {

    @ParameterizedTest
    @CsvSource({
            "CAD, 20.00, 2000, cad",
            "XOF, 5000.00, 5000, xof"
    })
    void initiate_chargesTheServerResolvedCurrencyMinorUnits_withoutFxMetadata(
            String resolvedCurrency, String requestedAmount, long expectedMinor, String stripeCurrency) {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = topupRequest(requestedAmount);
        WalletTopupOrchestrator orchestrator = orchestrator(userId, resolvedCurrency);
        AtomicReference<PaymentIntentCreateParams> capturedParams = new AtomicReference<>();

        try (MockedStatic<PaymentIntent> mockedPi = mockStatic(PaymentIntent.class)) {
            PaymentIntent fakePi = mock(PaymentIntent.class);
            when(fakePi.getClientSecret()).thenReturn("secret_test");
            mockedPi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenAnswer(invocation -> {
                        capturedParams.set(invocation.getArgument(0));
                        return fakePi;
                    });

            WalletTopupResponse response = orchestrator.initiate(userId, request);

            assertThat(response.getClientSecret()).isEqualTo("secret_test");
        }

        PaymentIntentCreateParams params = capturedParams.get();
        assertThat(params.getAmount()).isEqualTo(expectedMinor);
        assertThat(params.getCurrency()).isEqualTo(stripeCurrency);
        assertThat(params.getExtraParams()).isNullOrEmpty();
        assertThat(params.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "wallet_topup", "true",
                "user_id", userId.toString(),
                "wallet_currency", stripeCurrency));
    }

    /**
     * Régression du blocage « rechargez encore » : un client qui envoie EUR (ou
     * qui n'envoie rien, l'ancien repli silencieux) ne doit plus pouvoir créditer
     * autre chose que le portefeuille réellement contrôlé côté commission.
     */
    @ParameterizedTest
    @CsvSource({"EUR", "USD"})
    void initiate_ignoresTheCurrencySentByTheClient(String currencySentByClient) {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = topupRequest("5000.00");
        request.setCurrencyCode(currencySentByClient);
        // Le portefeuille réel du voyageur, celui que relit acceptCashBid.
        WalletTopupOrchestrator orchestrator = orchestrator(userId, "XOF");
        AtomicReference<PaymentIntentCreateParams> capturedParams = new AtomicReference<>();

        try (MockedStatic<PaymentIntent> mockedPi = mockStatic(PaymentIntent.class)) {
            PaymentIntent fakePi = mock(PaymentIntent.class);
            when(fakePi.getClientSecret()).thenReturn("secret_test");
            mockedPi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenAnswer(invocation -> {
                        capturedParams.set(invocation.getArgument(0));
                        return fakePi;
                    });

            orchestrator.initiate(userId, request);
        }

        assertThat(capturedParams.get().getCurrency()).isEqualTo("xof");
        assertThat(capturedParams.get().getMetadata()).containsEntry("wallet_currency", "xof");
    }

    @Test
    void initiate_withoutAnyCurrencyFromTheClient_stillCreditsTheWalletCurrency() {
        UUID userId = UUID.randomUUID();
        // currencyCode absent : l'ancien code retombait sur EUR en silence.
        WalletTopupRequest request = topupRequest("5000.00");
        WalletTopupOrchestrator orchestrator = orchestrator(userId, "XOF");
        AtomicReference<PaymentIntentCreateParams> capturedParams = new AtomicReference<>();

        try (MockedStatic<PaymentIntent> mockedPi = mockStatic(PaymentIntent.class)) {
            PaymentIntent fakePi = mock(PaymentIntent.class);
            when(fakePi.getClientSecret()).thenReturn("secret_test");
            mockedPi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenAnswer(invocation -> {
                        capturedParams.set(invocation.getArgument(0));
                        return fakePi;
                    });

            orchestrator.initiate(userId, request);
        }

        assertThat(capturedParams.get().getCurrency()).isEqualTo("xof");
    }

    @Test
    void initiate_propagatesUnsupportedCurrencyAsBusinessError() {
        UUID userId = UUID.randomUUID();
        WalletTopupOrchestrator orchestrator = orchestrator(userId, "BTC");

        assertThatThrownBy(() -> orchestrator.initiate(userId, topupRequest("20.00")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("unsupported-currency"));
    }

    private static WalletTopupRequest topupRequest(String amount) {
        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal(amount));
        request.setPaymentMethod("STRIPE");
        return request;
    }

    private static WalletTopupOrchestrator orchestrator(UUID userId, String resolvedCurrency) {
        ActiveCurrencyResolver resolver = mock(ActiveCurrencyResolver.class);
        when(resolver.resolve(userId)).thenReturn(resolvedCurrency);
        return new WalletTopupOrchestrator(new CurrencyCatalog(), resolver);
    }
}
