package com.yadony.api.matching;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Règle unique « le trajet est-il parti ? », partagée par la bascule IN_PROGRESS des
 * trajets ({@link AnnouncementService#triggerInProgressTransitions}) et par la
 * confirmation de livraison ({@code TrackingService.confirmDelivery}) : un code de
 * retrait saisi avant le départ terminait le colis et libérait le séquestre au
 * voyageur avant le transport (FLUTTER-CB).
 *
 * <p>Un trajet est « parti » quand son instant de départ, (date + heure) interprétées
 * dans le fuseau PROPRE du trajet ({@link TripTimezones#zoneOf}), est atteint ou dépassé par
 * {@code now}. Sans heure de départ, il est parti une fois sa date locale entièrement
 * passée. Statique, avec {@code now} en paramètre : testable de façon déterministe,
 * indépendamment de l'horloge et du fuseau du serveur.
 */
public final class DepartureRules {

    private DepartureRules() {}

    public static boolean hasDeparted(AnnouncementEntity a, Instant now) {
        LocalDate depDate = a.getDepartureDate();
        if (depDate == null) {
            return false;
        }
        ZoneId zone = TripTimezones.zoneOf(a.getTimezone());
        LocalTime depTime = a.getDepartureTime();
        if (depTime != null) {
            Instant departureAt = depDate.atTime(depTime).atZone(zone).toInstant();
            return !departureAt.isAfter(now);
        }
        return depDate.isBefore(now.atZone(zone).toLocalDate());
    }
}
