package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightOfferDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/** Ligne Travelpayouts → offre affichable (nom de compagnie traduit, devise explicite). */
@Component
public class FlightOfferMapper {

    private static final Pattern IATA_CODE = Pattern.compile("^[A-Z0-9]{2,3}$");

    private final AirlineDirectory airlines;
    private final String logoUrlTemplate;

    /**
     * @param logoUrlTemplate modèle d'URL du logo, {@code {code}} remplacé par le code IATA ;
     *                        vide = pas de logo (l'app retombe sur une pastille avec le code)
     */
    public FlightOfferMapper(AirlineDirectory airlines,
                             @Value("${yadony.flights.logo-url-template:}") String logoUrlTemplate) {
        this.airlines = airlines;
        this.logoUrlTemplate = logoUrlTemplate == null ? "" : logoUrlTemplate.trim();
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
                p.link(),
                logoUrl(airlineCode));
    }

    /** Le code vient du partenaire : on ne l'insère dans l'URL que s'il a la forme d'un code IATA. */
    private String logoUrl(String airlineCode) {
        if (logoUrlTemplate.isEmpty() || airlineCode == null || !IATA_CODE.matcher(airlineCode).matches()) {
            return null;
        }
        return logoUrlTemplate.replace("{code}", airlineCode);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
