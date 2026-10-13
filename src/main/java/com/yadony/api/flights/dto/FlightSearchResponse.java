package com.yadony.api.flights.dto;

import java.util.List;

/**
 * Résultat d'une recherche, offres triées par prix croissant. Les prix sont indicatifs
 * (cache du partenaire) : l'app l'affiche, le prix définitif est celui du site partenaire.
 *
 * @param searchPath       chemin de recherche Aviasales du trajet ({@code /search/PAR1212ABJ1}), pour
 *                         « Voir toutes les compagnies » : à envoyer comme {@code offerPath} à
 *                         {@code POST /flights/clicks}. {@code null} sans jour de départ utilisable
 *                         (pas de date, ou mois en cours) : l'app masque alors le lien. Toujours
 *                         construit sur les dates demandées, même avec {@code nearbyDates}.
 * @param nearbyDates      {@code true} quand aucune offre n'existait aux jours exacts demandés et que
 *                         les offres rendues partent (et reviennent) à {@code nearbyDaysWindow} jours
 *                         près ; {@code null} (absent du JSON) sinon. Jamais {@code false}.
 * @param nearbyDaysWindow écart maximal en jours, de part et d'autre de chaque date demandée, des
 *                         offres rendues ; présent seulement avec {@code nearbyDates}
 */
public record FlightSearchResponse(
        String originCode,
        String destinationCode,
        boolean roundTrip,
        String currency,
        List<FlightOfferDto> offers,
        String searchPath,
        Boolean nearbyDates,
        Integer nearbyDaysWindow) {

    /** Résultat aux dates demandées, sans repli. */
    public FlightSearchResponse(String originCode, String destinationCode, boolean roundTrip, String currency,
                                List<FlightOfferDto> offers, String searchPath) {
        this(originCode, destinationCode, roundTrip, currency, offers, searchPath, null, null);
    }
}
