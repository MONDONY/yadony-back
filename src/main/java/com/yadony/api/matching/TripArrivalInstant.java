package com.yadony.api.matching;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Instant d'arrivée prévu d'un trajet, partagé par le suivi approximatif du voyageur
 * (statut « sans nouvelles ») et par le rappel d'arrivée.
 *
 * <p>Jour d'arrivée effectif ({@link ArrivalRules#effectiveArrivalDate}) + heure d'arrivée,
 * lus dans le fuseau de la VILLE D'ARRIVÉE (référentiel {@code cities}, ville + pays), à
 * défaut dans le fuseau du trajet (celui du départ). Un Paris → Abidjan arrivant à 06:30
 * n'est pas à l'heure de Paris. Vide sans heure d'arrivée ou sans jour d'arrivée.
 */
@Component
public class TripArrivalInstant {

    private final AnnouncementRepository announcementRepository;

    public TripArrivalInstant(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    public Optional<OffsetDateTime> of(AnnouncementEntity a) {
        if (a.getArrivalTime() == null) {
            return Optional.empty();
        }
        var day = ArrivalRules.effectiveArrivalDate(a);
        if (day == null) {
            return Optional.empty();
        }
        return Optional.of(day.atTime(a.getArrivalTime()).atZone(arrivalZone(a)).toOffsetDateTime());
    }

    private java.time.ZoneId arrivalZone(AnnouncementEntity a) {
        String city = a.getArrivalCity();
        if (city != null && !city.isBlank()) {
            String country = a.getArrivalCountryCode() == null ? "" : a.getArrivalCountryCode();
            Optional<String> zone = announcementRepository.findTimezoneByCityName(city.trim(), country);
            if (zone != null && zone.isPresent()) {
                try {
                    return java.time.ZoneId.of(zone.get().trim());
                } catch (java.time.DateTimeException ignored) {
                    // fuseau inconnu de la JVM : repli sur le fuseau du trajet
                }
            }
        }
        return TripTimezones.zoneOf(a.getTimezone());
    }
}
