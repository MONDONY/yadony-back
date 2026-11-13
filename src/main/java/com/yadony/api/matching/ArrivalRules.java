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

    /**
     * Décalage d'arrivée d'un modèle ou d'une série (jours après le départ) contre la durée maximale
     * du mode, à l'enregistrement : refusé tout de suite (422 {@code arrival-too-far}, avec le nombre de
     * jours) plutôt que perdu en silence à chaque génération d'occurrence. Absent : même jour.
     */
    public static void assertOffsetWithinMode(Integer arrivalDayOffset, TransportMode mode) {
        int maxDays = maxDaysAfterDeparture(mode);
        if (arrivalDayOffset != null && arrivalDayOffset > maxDays) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-too-far", "problem.arrival-too-far", maxDays);
        }
    }

    public static void validate(LocalDate departureDate, LocalTime departureTime,
                                LocalDate arrivalDate, LocalTime arrivalTime, TransportMode mode) {
        if (arrivalDate == null || departureDate == null) {
            return;
        }
        if (arrivalDate.isBefore(departureDate)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-before-departure", "problem.arrival-before-departure");
        }
        int maxDays = maxDaysAfterDeparture(mode);
        if (arrivalDate.isAfter(departureDate.plusDays(maxDays))) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-too-far", "problem.arrival-too-far", maxDays);
        }
        if (arrivalDate.isEqual(departureDate) && arrivalTime != null && departureTime != null
                && !arrivalTime.isAfter(departureTime)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-time-before-departure", "problem.arrival-time-before-departure");
        }
    }

    /**
     * Fourchette d'arrivée estimée d'un trajet bateau : début ({@code arrivalDate}) et fin
     * ({@code arrivalDateMax}) tous deux présents, la fin pas avant le début (422
     * {@code arrival-range-invalid}), et au plus départ + 120 jours (422 {@code arrival-too-far}, le
     * plafond du CHECK {@code chk_announcements_arrival_date_max}). Le début contre le départ reste
     * contrôlé par {@link #validate}.
     */
    public static void validateRange(LocalDate departureDate, LocalDate arrivalDate, LocalDate arrivalDateMax) {
        if (arrivalDate == null || arrivalDateMax == null || arrivalDateMax.isBefore(arrivalDate)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-range-invalid",
                    "problem.arrival-range-invalid");
        }
        if (departureDate != null && arrivalDateMax.isAfter(departureDate.plusDays(BOAT_MAX_DAYS))) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "arrival-too-far",
                    "problem.arrival-too-far", BOAT_MAX_DAYS);
        }
    }

    /**
     * Jour d'arrivée effectif : la date d'arrivée, sinon le jour du départ. Pour un bateau, c'est
     * le début de la fourchette (arrivée au plus tôt), lu par le suivi et le rappel d'arrivée.
     */
    public static LocalDate effectiveArrivalDate(AnnouncementEntity announcement) {
        return announcement.getArrivalDate() != null
                ? announcement.getArrivalDate()
                : announcement.getDepartureDate();
    }

    /**
     * Jour d'arrivée au plus tard : la fin de la fourchette d'un bateau ({@code arrivalDateMax}),
     * sinon le jour d'arrivée effectif.
     */
    public static LocalDate latestArrivalDate(AnnouncementEntity announcement) {
        return announcement.getArrivalDateMax() != null
                ? announcement.getArrivalDateMax()
                : effectiveArrivalDate(announcement);
    }

    /**
     * Expiration du code de retrait : jour d'arrivée réel + heure d'arrivée + 24 h, sinon
     * 72 h après le début du jour d'arrivée. Un vol de nuit arrive le lendemain : partir du
     * jour du départ faisait expirer le code à l'atterrissage (FLUTTER-4E). Recalculée
     * quand le trajet est reporté. Pour un bateau, le jour d'arrivée est la FIN de la fourchette
     * ({@link #latestArrivalDate}) : un code calé sur l'arrivée au plus tôt expirerait avant
     * qu'un navire en retard dans sa fourchette n'accoste.
     */
    public static java.time.LocalDateTime pickupCodeExpiry(AnnouncementEntity announcement) {
        return pickupCodeExpiry(latestArrivalDate(announcement), announcement.getArrivalTime());
    }

    /** Même règle sur un jour d'arrivée donné (l'annonce de retard la calcule sur la fin de fourchette). */
    public static java.time.LocalDateTime pickupCodeExpiry(LocalDate arrivalDay, LocalTime arrivalTime) {
        if (arrivalTime != null) {
            return arrivalDay.atTime(arrivalTime).plusDays(1);
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
        return renewedPickupCodeExpiry(latestArrivalDate(announcement), announcement.getArrivalTime());
    }

    /** Même règle sur un jour d'arrivée donné : l'expiration prévue, jamais avant maintenant + 24 h. */
    public static java.time.LocalDateTime renewedPickupCodeExpiry(LocalDate arrivalDay, LocalTime arrivalTime) {
        java.time.LocalDateTime planned = pickupCodeExpiry(arrivalDay, arrivalTime);
        java.time.LocalDateTime floor = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .plus(RENEWED_CODE_MIN_VALIDITY);
        return planned.isAfter(floor) ? planned : floor;
    }
}
