package com.yadony.api.payments.currency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeFxQuoteServiceTest {

    @Test
    void createsOneHourQuoteAndConvertsCanonicalEurAmountWithStripeRate() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("https://api.stripe.com/v1/fx_quotes"),
                eq(HttpMethod.POST),
                any(),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("""
                        {
                          "id": "fxq_test_cad",
                          "lock_expires_at": 1893459600,
                          "rates": {"cad": {"exchange_rate": 1.47}}
                        }
                        """, HttpStatus.OK));

        StripeFxQuoteService service = new StripeFxQuoteService(
                restTemplate,
                new ObjectMapper(),
                "sk_test_secret",
                "2025-03-31.preview",
                Duration.ofHours(1));

        StripeFxQuoteService.FxQuoteSnapshot quote = service.createPaymentQuote(SupportedCurrency.CAD);

        assertThat(quote.id()).isEqualTo("fxq_test_cad");
        assertThat(quote.exchangeRate()).isEqualByComparingTo("1.47");
        assertThat(quote.localUnitsPerEur()).isEqualByComparingTo("0.6802721088");
        assertThat(quote.expiresAt().getEpochSecond()).isEqualTo(1893459600L);

        ArgumentCaptor<HttpEntity<?>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://api.stripe.com/v1/fx_quotes"),
                eq(HttpMethod.POST),
                requestCaptor.capture(),
                eq(String.class));
        assertThat(requestCaptor.getValue().getHeaders().getFirst("Stripe-Version"))
                .isEqualTo("2025-03-31.preview");
        String expectedAuth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("sk_test_secret:".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(requestCaptor.getValue().getHeaders().getFirst("Authorization"))
                .isEqualTo(expectedAuth);
    }

    @Test
    void doesNotCallStripeForEur() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        StripeFxQuoteService service = new StripeFxQuoteService(
                restTemplate,
                new ObjectMapper(),
                "sk_test_secret",
                "2025-03-31.preview",
                Duration.ofHours(1));

        assertThat(service.createPaymentQuote(SupportedCurrency.EUR)).isNull();
    }
}
