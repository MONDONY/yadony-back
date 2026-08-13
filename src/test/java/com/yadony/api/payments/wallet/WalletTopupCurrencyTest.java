package com.yadony.api.payments.wallet;

import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.common.YadonyBusinessException;
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

class WalletTopupCurrencyTest {

    @ParameterizedTest
    @CsvSource({
            "CAD, 20.00, 2000, cad",
            "XOF, 5000.00, 5000, xof"
    })
    void initiate_chargesRequestedCurrencyMinorUnits_withoutFxMetadata(
            String requestedCurrency, String requestedAmount, long expectedMinor, String stripeCurrency) {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = topupRequest(requestedCurrency, requestedAmount);
        WalletTopupOrchestrator orchestrator = orchestrator();
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

    @Test
    void initiate_propagatesUnsupportedCurrencyAsBusinessError() {
        WalletTopupOrchestrator orchestrator = orchestrator();

        assertThatThrownBy(() -> orchestrator.initiate(
                UUID.randomUUID(), topupRequest("BTC", "20.00")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("unsupported-currency"));
    }

    private static WalletTopupRequest topupRequest(String currency, String amount) {
        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal(amount));
        request.setPaymentMethod("STRIPE");
        request.setCurrencyCode(currency);
        return request;
    }

    private static WalletTopupOrchestrator orchestrator() {
        return new WalletTopupOrchestrator(new CurrencyCatalog());
    }
}
