package com.yadony.api.matching.dto;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.dto.NegotiationStartRequest;
import com.yadony.api.requests.dto.PackageRequestCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V380 : tous les DTO d'entrée qui écrivent dans une colonne de poids NUMERIC(8,2) sont bornés
 * à 999 999,99 kg. Au-delà, la validation Bean (422 {@code violations}) répond avant que la base ne
 * lève un « numeric field overflow » (500 SQL), y compris en kilo libre (D5 du pré-vol).
 */
@DisplayName("Bornes de poids des DTO (999 999,99 kg)")
class WeightDtoBoundsValidationTest {

    private static final BigDecimal MAX = new BigDecimal("999999.99");
    private static final BigDecimal ABOVE = new BigDecimal("1000000");

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean violatesWeight(Object dto, String field) {
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    void packageRequest_weight() {
        assertThat(violatesWeight(packageRequest(MAX), "weightKg")).isFalse();
        assertThat(violatesWeight(packageRequest(new BigDecimal("1200.5")), "weightKg")).isFalse();
        assertThat(violatesWeight(packageRequest(ABOVE), "weightKg")).isTrue();
        assertThat(violatesWeight(packageRequest(new BigDecimal("0.4")), "weightKg")).isTrue();
    }

    @Test
    void trip_availableKg() {
        assertThat(violatesWeight(trip(MAX), "availableKg")).isFalse();
        assertThat(violatesWeight(trip(ABOVE), "availableKg")).isTrue();
    }

    @Test
    void negotiation_travelerAvailableKg() {
        assertThat(violatesWeight(negotiation(MAX), "travelerAvailableKg")).isFalse();
        assertThat(violatesWeight(negotiation(ABOVE), "travelerAvailableKg")).isTrue();
    }

    @Test
    void bid_weightKg() {
        assertThat(violatesWeight(bid(MAX), "weightKg")).isFalse();
        assertThat(violatesWeight(bid(ABOVE), "weightKg")).isTrue();
        assertThat(violatesWeight(bid(null), "weightKg")).isFalse();
    }

    @Test
    void bidCheckout_weightKg() {
        assertThat(violatesWeight(checkout(MAX), "weightKg")).isFalse();
        assertThat(violatesWeight(checkout(ABOVE), "weightKg")).isTrue();
    }

    @Test
    void bidNegotiationStart_weightKg() {
        assertThat(violatesWeight(bidNegotiation(MAX), "weightKg")).isFalse();
        assertThat(violatesWeight(bidNegotiation(ABOVE), "weightKg")).isTrue();
    }

    private static PackageRequestCreateRequest packageRequest(BigDecimal kg) {
        return new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(7), 2,
                kg, "vetements", "Cadeau", new BigDecimal("28.00"), null,
                "10e", "Plateau", true, EnumSet.of(PaymentMethod.STRIPE), List.of(), null,
                null, null, "CAR");
    }

    private static AnnouncementRequest trip(BigDecimal kg) {
        LocalDate departure = LocalDate.now().plusDays(10);
        return new AnnouncementRequest(
                "Paris", "Dakar", departure, LocalTime.of(10, 0), LocalTime.of(22, 0),
                new AddressDto("CDG", 49.009, 2.547), new AddressDto("LSS", 14.739, -17.490),
                kg, BigDecimal.valueOf(5), TransportMode.CAR,
                null, null, null, null, null, null, null, null,
                departure.atTime(9, 0), null, null, null);
    }

    private static NegotiationStartRequest negotiation(BigDecimal kg) {
        return new NegotiationStartRequest(UUID.randomUUID(), new BigDecimal("40"),
                LocalDate.now().plusDays(10), kg, UUID.randomUUID(), null);
    }

    private static BidRequest bid(BigDecimal kg) {
        return new BidRequest(kg, "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", true, null, null, null, null, null, null);
    }

    private static BidCheckoutRequest checkout(BigDecimal kg) {
        return new BidCheckoutRequest(UUID.randomUUID(), kg, "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", true, null, null);
    }

    private static BidNegotiationStartRequest bidNegotiation(BigDecimal kg) {
        return new BidNegotiationStartRequest(kg, "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", true,
                "CASH", null, null, null, new BigDecimal("45.00"), null, null);
    }
}
