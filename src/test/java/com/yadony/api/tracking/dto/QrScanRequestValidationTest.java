package com.yadony.api.tracking.dto;

import com.yadony.api.tracking.TrackingEventType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Validation de QrScanRequest")
class QrScanRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private QrScanRequest request(BigDecimal lat, BigDecimal lon, String label, String photo) {
        return new QrScanRequest(UUID.randomUUID(), TrackingEventType.DEPART, lat, lon, label, photo, null);
    }

    @Test
    @DisplayName("un scan complet et borné passe")
    void validRequestPasses() {
        assertThat(validator.validate(request(new BigDecimal("48.85"), new BigDecimal("2.35"),
                "Paris, Gare de Lyon", "tracking/abc/1_DEPART.jpg"))).isEmpty();
    }

    @Test
    @DisplayName("une clé photo au-delà de la colonne (1024) est refusée en 422, plus en 500")
    void photoUrlTooLongIsRejected() {
        assertThat(validator.validate(request(null, null, null, "x".repeat(501)))).isNotEmpty();
    }

    @Test
    @DisplayName("un libellé GPS non borné est refusé")
    void gpsLabelTooLongIsRejected() {
        assertThat(validator.validate(request(null, null, "x".repeat(256), null))).isNotEmpty();
    }

    @Test
    @DisplayName("des coordonnées hors du globe sont refusées")
    void coordinatesOutOfRangeAreRejected() {
        assertThat(validator.validate(request(new BigDecimal("91"), new BigDecimal("0"), null, null))).isNotEmpty();
        assertThat(validator.validate(request(new BigDecimal("0"), new BigDecimal("-181"), null, null))).isNotEmpty();
    }
}
