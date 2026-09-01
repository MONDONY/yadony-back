package com.yadony.api.payments;

import com.yadony.api.payments.dto.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentResponseTest {

    @Test
    void exposes_transaction_currency_as_normalized_stripe_code() {
        PaymentResponse response = new PaymentResponse(
                UUID.randomUUID(), UUID.randomUUID(), "secret",
                new BigDecimal("51.00"), new BigDecimal("6.12"), "PENDING", "pi_123", "CAD");

        assertThat(response.getCurrency()).isEqualTo("cad");
    }

    @Test
    void payment_entity_defaults_to_eur_for_existing_domain_objects() {
        PaymentEntity payment = new PaymentEntity();

        // MAJUSCULES depuis V236 : convention du schéma entier, le CHECK refuse
        // désormais un code minuscule.
        assertThat(payment.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void create_payment_request_accepts_optional_currency_code() {
        var request = new com.yadony.api.payments.dto.CreatePaymentRequest();
        request.setCurrencyCode("CAD");

        assertThat(request.getCurrencyCode()).isEqualTo("CAD");
    }
}
