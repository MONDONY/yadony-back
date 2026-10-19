package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PawapayFailureCodesTest {

    @Test
    void toYadony_mapsEachKnownPawapayCode() {
        assertThat(PawapayFailureCodes.toYadony("INSUFFICIENT_BALANCE")).isEqualTo("insufficient-balance");
        assertThat(PawapayFailureCodes.toYadony("PAYMENT_NOT_APPROVED")).isEqualTo("not-approved");
        assertThat(PawapayFailureCodes.toYadony("PAYER_LIMIT_REACHED")).isEqualTo("payer-limit-reached");
        assertThat(PawapayFailureCodes.toYadony("INVALID_PHONE_NUMBER")).isEqualTo("invalid-number");
        assertThat(PawapayFailureCodes.toYadony("PAYER_NOT_FOUND")).isEqualTo("invalid-number");
        assertThat(PawapayFailureCodes.toYadony("RECIPIENT_NOT_FOUND")).isEqualTo("invalid-number");
        assertThat(PawapayFailureCodes.toYadony("PROVIDER_TEMPORARILY_UNAVAILABLE"))
                .isEqualTo("provider-unavailable");
        assertThat(PawapayFailureCodes.toYadony("SUBMIT_TIMEOUT")).isEqualTo("expired");
    }

    @Test
    void toYadony_unknownBlankOrNull_isOther() {
        assertThat(PawapayFailureCodes.toYadony("SOMETHING_NEW")).isEqualTo("other");
        assertThat(PawapayFailureCodes.toYadony("")).isEqualTo("other");
        assertThat(PawapayFailureCodes.toYadony(null)).isEqualTo("other");
    }
}
