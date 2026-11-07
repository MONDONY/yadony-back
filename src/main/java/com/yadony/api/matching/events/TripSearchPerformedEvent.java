package com.yadony.api.matching.events;

import java.time.LocalDate;

/**
 * Un utilisateur connecté vient de chercher des trajets sur un corridor (première page d'une
 * vraie liste, pas un simple compteur). Publié par {@code AnnouncementController}, écouté par
 * le package {@code alerts} pour retenir la recherche et suggérer plus tard un trajet qui
 * correspond, sans que l'utilisateur ait posé d'alerte.
 *
 * @param bookableCount trajets réservables trouvés (0 = recherche restée sans résultat)
 */
public record TripSearchPerformedEvent(
        String firebaseUid,
        String departureCity,
        String arrivalCity,
        LocalDate dateFrom,
        LocalDate dateTo,
        long bookableCount
) {}
