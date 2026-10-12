package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightOfferDto;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Ligne Travelpayouts → offre affichable (nom de compagnie traduit, devise explicite). */
@Component
public class FlightOfferMapper {

    private final AirlineDirectory airlines;

    public FlightOfferMapper(AirlineDirectory airlines) {
        this.airlines = airlines;
    }

    public FlightOfferDto toDto(TravelpayoutsPrice p, String currency, String language) {
        String airlineCode = p.airline() == null ? null : p.airline().toUpperCase(Locale.ROOT);
        return new FlightOfferDto(
                upper(p.origin()),
                upper(p.destination()),
                upper(p.originAirport()),
                upper(p.destinationAirport()),
                p.departureAt(),
                p.returnAt(),
                p.price(),
                currency,
                airlineCode,
                airlines.nameOf(airlineCode, language),
                p.flightNumber(),
                p.transfers(),
                p.returnTransfers(),
                p.duration(),
                p.gate(),
                p.link());
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
