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

    /**
     * Plafond absolu de la base : le CHECK {@code chk_announcements_arrival_date} (V379) accepte
     * jusqu'à 120 jours, ce qu'il faut à un bateau. Chaque mode a sa propre limite dessous.
     */
    private static final int BOAT_MAX_DAYS = 120;

    private ArrivalRules() {}

    /**
     * Durée maximale entre le départ et l'arrivée selon le mode : au-delà, ce n'est plus un
     * trajet mais un séjour. Avion, train et autre : 3 jours (vols de nuit, escales) ; voiture
     * et bus : 14 jours (routes longues, frontières) ; bateau : 120 jours (cargos, traversées
     * avec escales). Mode absent : 3 jours, comme avant les modes.
     */
    public static int maxDaysAfterDeparture(TransportMode mode) {
        if (mode == null) {
            return 3;
        }
        return switch (mode) {
            case PLANE, TRAIN, OTHER -> 3;
            case CAR, BUS -> 14;
            case BOAT -> BOAT_MAX_DAYS;
        };
    }

    public static void validate(LocalDate departureDate, LocalTime departureTime,
                                LocalDate arrivalDate, LocalTime arrivalTime, TransportMode mode) {
        if (arrivalDate == null || departureDate == null) {
            return;
        }
        if (arrivalDate.isBefore(departureDate)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "arrival-before-departure", "Arrivée avant le départ",
                    "La date d'arrivée ne peut pas précéder la date de départ");
        }
        int maxDays = maxDaysAfterDeparture(mode);
        if (arrivalDate.isAfter(departureDate.plusDays(maxDays))) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "arrival-too-far", "Arrivée trop tardive",
                    "La date d'arrivée doit être au plus " + maxDays
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

    /**
     * Expiration du code de retrait : jour d'arrivée réel + heure d'arrivée + 24 h, sinon
     * 72 h après le début du jour d'arrivée. Un vol de nuit arrive le lendemain : partir du
     * jour du départ faisait expirer le code à l'atterrissage (FLUTTER-4E). Recalculée
     * quand le trajet est reporté.
     */
    public static java.time.LocalDateTime pickupCodeExpiry(AnnouncementEntity announcement) {
        LocalDate arrivalDay = effectiveArrivalDate(announcement);
        if (announcement.getArrivalTime() != null) {
            return arrivalDay.atTime(announcement.getArrivalTime()).plusDays(1);
        }
        return arrivalDay.atStartOfDay().plusDays(3);
    }

    /** Fenêtre minimale d'un code de retrait régénéré. */
    static final java.time.Duration RENEWED_CODE_MIN_VALIDITY = java.time.Duration.ofHours(24);

    /**
     * Expiration d'un code de retrait régénéré (nouveau code demandé par l'expéditeur, ou
     * changement de destinataire). Remis plus de 24 h après l'arrivée prévue, le colis
     * recevait un code déjà expiré : la livraison ne pouvait plus jamais être confirmée
     * (FLUTTER-BA). Le nouveau code vaut donc au moins 24 h à partir de maintenant.
     */
    public static java.time.LocalDateTime renewedPickupCodeExpiry(AnnouncementEntity announcement) {
        java.time.LocalDateTime planned = pickupCodeExpiry(announcement);
        java.time.LocalDateTime floor = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .plus(RENEWED_CODE_MIN_VALIDITY);
        return planned.isAfter(floor) ? planned : floor;
    }
}
