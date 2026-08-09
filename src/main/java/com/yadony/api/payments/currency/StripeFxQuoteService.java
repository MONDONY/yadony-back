package com.yadony.api.payments.currency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Adapter for Stripe's FX Quotes preview API.
 *
 * stripe-java 26.12.0 does not contain the FxQuote resource yet, so this small
 * adapter calls the REST endpoint while PaymentIntent/Transfer builders carry
 * the supported {@code fx_quote} extra parameter.
 */
@Service
public class StripeFxQuoteService {

    private static final String FX_QUOTES_URL = "https://api.stripe.com/v1/fx_quotes";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secretKey;
    private final String stripeApiVersion;
    private final String lockDuration;
    private final Duration fallbackTtl;

    @Autowired
    public StripeFxQuoteService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${stripe.secret-key}") String secretKey,
            @Value("${stripe.fx-quotes.api-version:2025-03-31.preview}") String stripeApiVersion,
            @Value("${stripe.fx-quotes.lock-duration:hour}") String lockDuration,
            @Value("${stripe.fx-quotes.fallback-ttl:PT1H}") Duration fallbackTtl) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey");
        this.stripeApiVersion = Objects.requireNonNull(stripeApiVersion, "stripeApiVersion");
        this.lockDuration = Objects.requireNonNull(lockDuration, "lockDuration");
        this.fallbackTtl = Objects.requireNonNull(fallbackTtl, "fallbackTtl");
    }

    public StripeFxQuoteService(RestTemplate restTemplate,
                                ObjectMapper objectMapper,
                                String secretKey,
                                String stripeApiVersion,
                                Duration fallbackTtl) {
        this(restTemplate, objectMapper, secretKey, stripeApiVersion, "hour", fallbackTtl);
    }

    /** Creates a quote converting the customer's presentment currency to EUR. */
    public FxQuoteSnapshot createPaymentQuote(SupportedCurrency presentmentCurrency) {
        Objects.requireNonNull(presentmentCurrency, "presentmentCurrency");
        if (presentmentCurrency == SupportedCurrency.EUR) {
            return null;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("to_currency", SupportedCurrency.EUR.code());
        form.add("from_currencies[]", presentmentCurrency.code());
        form.add("lock_duration", lockDuration);
        form.add("usage[type]", "payment");

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(secretKey, "");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set("Stripe-Version", stripeApiVersion);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    FX_QUOTES_URL,
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode rateNode = root.path("rates").path(presentmentCurrency.code()).path("exchange_rate");
            if (!root.hasNonNull("id") || !rateNode.isNumber() || rateNode.decimalValue().signum() <= 0) {
                throw new IllegalStateException("Stripe returned an incomplete FX quote");
            }
            long expiresAtEpoch = root.path("lock_expires_at").asLong(0L);
            Instant expiresAt = expiresAtEpoch > 0
                    ? Instant.ofEpochSecond(expiresAtEpoch)
                    : Instant.now().plus(fallbackTtl);
            BigDecimal exchangeRate = rateNode.decimalValue();
            BigDecimal localUnitsPerEur = BigDecimal.ONE.divide(exchangeRate, 10, RoundingMode.HALF_UP);
            return new FxQuoteSnapshot(root.get("id").asText(), presentmentCurrency,
                    exchangeRate, localUnitsPerEur, expiresAt);
        } catch (Exception exception) {
            if (exception instanceof YadonyBusinessException businessException) {
                throw businessException;
            }
            throw new YadonyBusinessException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "stripe-fx-quote-unavailable",
                    "Exchange rate unavailable",
                    "Le taux de change Stripe est temporairement indisponible.");
        }
    }

    public record FxQuoteSnapshot(String id,
                                  SupportedCurrency presentmentCurrency,
                                  BigDecimal exchangeRate,
                                  BigDecimal localUnitsPerEur,
                                  Instant expiresAt) {
    }
}
