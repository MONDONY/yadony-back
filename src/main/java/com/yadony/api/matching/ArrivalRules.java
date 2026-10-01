package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Règles de la date d'arrivée d'un trajet (FLUTTER-4E). Partagées par la
 * publication d'un trajet et par le trajet dédié d'une négociation.
 *
 * <p>Tolérante quand {@code arrivalDate} est absente : les apps antérieures publient
 * des vols de nuit (arrivée 06:30 pour un départ 22:00) sans date d'arrivée, et
 * l'arrivée est alors lue comme le même jour, ce qu'elles faisaient déjà.
 */
public final class ArrivalRules {

    /** Au-delà, ce n'est plus un trajet mais un séjour. Aligné sur le CHECK de V276. */
    public static final int MAX_DAYS_AFTER_DEPARTURE = 3;

    private ArrivalRules() {}

    public static void validate(LocalDate departureDate, LocalTime departureTime,
                                LocalDate arrivalDate, LocalTime arrivalTime) {
        if (arrivalDate == null || departureDate == null) {
            return;
        }
        if (arrivalDate.isBefore(departureDate)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "arrival-before-departure", "Arrivée avant le départ",
                    "La date d'arrivée ne peut pas précéder la date de départ");
        }
        if (arrivalDate.isAfter(departureDate.plusDays(MAX_DAYS_AFTER_DEPARTURE))) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "arrival-too-far", "Arrivée trop tardive",
                    "La date d'arrivée doit être au plus " + MAX_DAYS_AFTER_DEPARTURE
                            + " jours après le départ");
        }
        if (arrivalDate.isEqual(departureDate) && arrivalTime != null && departureTime != null
                && !arrivalTime.isAfter(departureTime)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "arrival-time-before-departure", "Heure d'arrivée avant le départ",
                    "Le même jour, l'heure d'arrivée doit suivre l'heure de départ");
        }
    }

    /** Jour d'arrivée effectif : la date d'arrivée, sinon le jour du départ. */
    public static LocalDate effectiveArrivalDate(AnnouncementEntity announcement) {
        return announcement.getArrivalDate() != null
                ? announcement.getArrivalDate()
                : announcement.getDepartureDate();
    }
}
