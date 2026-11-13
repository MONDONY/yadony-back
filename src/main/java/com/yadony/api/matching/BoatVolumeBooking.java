package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/**
 * Garde provisoire du lot 3 : un trajet bateau vendu au m³ ({@link AnnouncementEntity#isPricedPerM3()})
 * n'a ni kilos ni prix au kilo, alors que toute la réservation (offre ferme, paiement carte, proposition
 * de prix, liaison à une demande de colis) calcule encore en kilos. Tant que le prix et la capacité au
 * m³ ne sont pas en place (tâche BB2), ces entrées le refusent en 422 {@code boat-volume-booking-unavailable}
 * au lieu de calculer un prix faux ou de tomber sur un kilo nul.
 *
 * <p>Aucun colis ne peut donc exister sur un tel trajet : les chemins d'acceptation, d'annulation et de
 * paiement, qui partent d'un colis, ne le rencontrent pas. Un trajet au kilo, bateau compris, passe.
 */
public final class BoatVolumeBooking {

    public static final String ERROR_CODE = "boat-volume-booking-unavailable";

    private BoatVolumeBooking() {}

    public static void assertBookableByKg(AnnouncementEntity trip) {
        if (trip != null && trip.isPricedPerM3()) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE,
                    "problem." + ERROR_CODE);
        }
    }
}
