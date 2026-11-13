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
 *
 * <p>Deux lectures : {@link #of} au plus tôt (début de la fourchette d'un bateau : le rappel « êtes-vous
 * arrivé ? »), {@link #latestOf} au plus tard (fin de la fourchette : « sans nouvelles » ne se déclare pas
 * tant que le navire est encore dans sa fourchette). Hors bateau, les deux sont identiques.
 */
@Component
public class TripArrivalInstant {

    private final AnnouncementRepository announcementRepository;

    public TripArrivalInstant(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    /** Arrivée au plus tôt : début de la fourchette d'un bateau, jour d'arrivée sinon. */
    public Optional<OffsetDateTime> of(AnnouncementEntity a) {
        return at(a, ArrivalRules.effectiveArrivalDate(a));
    }

    /** Arrivée au plus tard : fin de la fourchette d'un bateau, jour d'arrivée sinon. */
    public Optional<OffsetDateTime> latestOf(AnnouncementEntity a) {
        return at(a, ArrivalRules.latestArrivalDate(a));
    }

    private Optional<OffsetDateTime> at(AnnouncementEntity a, java.time.LocalDate day) {
        if (a.getArrivalTime() == null || day == null) {
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
