package com.yadony.api.flights;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Fabriques partagées par les tests du package vols. */
final class FlightTestData {

    /** 15 octobre 2026, 10 h UTC. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC);

    private FlightTestData() {
    }

    static TravelpayoutsPrice price(String amount) {
        return price("PAR", "ABJ", amount);
    }

    static TravelpayoutsPrice price(String origin, String destination, String amount) {
        return new TravelpayoutsPrice(origin, destination, "CDG", destination, "2026-12-04T11:30:00+01:00", null,
                new BigDecimal(amount), "AT", "781", 1, null, 580, "Kiwi.com",
                "/search/" + origin + "0412" + destination + "1?t=X" + amount);
    }

    static FlightPriceAlertEntity alert(UUID owner, String maxPrice) {
        FlightPriceAlertEntity a = new FlightPriceAlertEntity();
        setId(a, UUID.randomUUID());
        a.setOwnerId(owner);
        a.setOriginCode("PAR");
        a.setDestinationCode("ABJ");
        a.setOriginLabel("Paris");
        a.setDestinationLabel("Abidjan");
        a.setMaxPrice(new BigDecimal(maxPrice));
        a.setCurrency("EUR");
        a.setActive(true);
        return a;
    }

    static void setId(Object target, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
