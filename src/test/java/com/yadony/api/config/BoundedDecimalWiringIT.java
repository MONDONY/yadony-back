package com.yadony.api.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Le bornage des décimaux doit être actif sur l'ObjectMapper ET sur le service de conversion
 * réellement utilisés par Spring MVC, pas seulement dans un mapper de test : un
 * {@code {"amount":1e10000000}} occupait sinon un thread environ 24 s (28/10).
 */
@SpringBootTest
@ActiveProfiles("test")
class BoundedDecimalWiringIT {

    record Amount(BigDecimal amount) {}

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    @Qualifier("mvcConversionService")
    ConversionService mvcConversionService;

    @Test
    void springObjectMapper_rejectsAHugeExponentQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":1e10000000}", Amount.class))
                        .isInstanceOf(JsonMappingException.class));
    }

    @Test
    void springObjectMapper_rejectsAHugeExponentSentAsString() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":\"1e10000000\"}", Amount.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    void springObjectMapper_keepsNormalAmounts() throws Exception {
        assertThat(objectMapper.readValue("{\"amount\":12.50}", Amount.class).amount())
                .isEqualByComparingTo("12.50");
        assertThat(objectMapper.readValue("{\"amount\":null}", Amount.class).amount()).isNull();
    }

    @Test
    void mvcConversion_rejectsAHugeRequestParam() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> mvcConversionService.convert("1e10000000", BigDecimal.class))
                        .isInstanceOf(ConversionFailedException.class));
    }

    @Test
    void mvcConversion_keepsNormalParams() {
        assertThat(mvcConversionService.convert("2.5", BigDecimal.class)).isEqualByComparingTo("2.5");
        assertThat(mvcConversionService.convert(" ", BigDecimal.class)).isNull();
    }
}
