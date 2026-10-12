package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightOfferDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** Ligne Travelpayouts → offre affichable (nom de compagnie traduit, devise explicite). */
@Component
public class FlightOfferMapper {

    private static final Pattern IATA_CODE = Pattern.compile("^[A-Z0-9]{2,3}$");

    private final AirlineDirectory airlines;
    private final AirportTimeZones timeZones;
    private final String logoUrlTemplate;

    /**
     * @param logoUrlTemplate modèle d'URL du logo, {@code {code}} remplacé par le code IATA ;
     *                        vide = pas de logo (l'app retombe sur une pastille avec le code)
     */
    public FlightOfferMapper(AirlineDirectory airlines, AirportTimeZones timeZones,
                             @Value("${yadony.flights.logo-url-template:}") String logoUrlTemplate) {
        this.airlines = airlines;
        this.timeZones = timeZones;
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
                logoUrl(airlineCode),
                p.durationTo(),
                p.returnAt() == null ? null : p.durationBack(),
                arrival(p.departureAt(), p.durationTo(), p.destinationAirport(), p.destination()),
                p.returnAt() == null ? null
                        : arrival(p.returnAt(), p.durationBack(), p.originAirport(), p.origin()));
    }

    /**
     * Départ + durée, exprimé à l'heure locale de l'arrivée. {@code null} dès qu'un élément manque
     * ou est illisible : mieux vaut pas d'heure qu'une heure fausse.
     */
    String arrival(String departureAt, Integer minutes, String airportCode, String cityCode) {
        if (departureAt == null || minutes == null || minutes < 0) return null;
        OffsetDateTime departure;
        try {
            departure = OffsetDateTime.parse(departureAt);
        } catch (DateTimeException e) {
            return null;
        }
        ZoneId zone = timeZones.zoneOf(airportCode, cityCode);
        if (zone == null) return null;
        return departure.plusMinutes(minutes).atZoneSameInstant(zone).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
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
