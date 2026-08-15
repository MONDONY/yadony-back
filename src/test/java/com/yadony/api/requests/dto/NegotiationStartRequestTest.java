package com.yadony.api.requests.dto;

import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NegotiationStartRequestTest {

    @Test
    void createDedicatedTrip_defaultsToFalse_whenOmitted() {
        NegotiationStartRequest req = new NegotiationStartRequest(
            UUID.randomUUID(), new BigDecimal("42.00"), LocalDate.now().plusDays(5),
            new BigDecimal("3.0"), UUID.randomUUID(), null
        );
        assertThat(req.createDedicatedTrip()).isFalse();
        assertThat(req.dedicatedTrip()).isNull();
    }

    @Test
    void createDedicatedTrip_carriesDedicatedTripPayload() {
        NegotiationCreateDedicatedTripRequest dedicated = new NegotiationCreateDedicatedTripRequest(
            LocalDate.now().plusDays(5), null, null,
            new AddressDto("Pickup", 48.8, 2.3), new AddressDto("Delivery", 5.3, -4.0),
            null, null, null, PaymentMethod.STRIPE, false
        );
        NegotiationStartRequest req = new NegotiationStartRequest(
            UUID.randomUUID(), new BigDecimal("42.00"), LocalDate.now().plusDays(5),
            new BigDecimal("3.0"), null, null, true, dedicated
        );
        assertThat(req.createDedicatedTrip()).isTrue();
        assertThat(req.dedicatedTrip()).isSameAs(dedicated);
    }
}
