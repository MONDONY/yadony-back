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
 *                         offres rendues (3, ou 7 si rien à 3 jours) ; présent seulement avec {@code nearbyDates}
 * @param similarOffers    recherche aller-retour à jours précis qui rend moins de 5 offres (exactes ou
 *                         proches) : jusqu'à 10 autres allers-retours du même trajet (même filtre direct,
 *                         même devise) dont la durée de séjour est à 7 jours près de celle demandée, départ
 *                         le mois demandé, le précédent (s'il n'est pas passé) ou le suivant ; triées par
 *                         prix, sans les offres déjà dans {@code offers}. {@code null} (absent du JSON) si
 *                         la liste est vide ou hors de ces conditions (aller simple, mois entier, ≥ 5 offres)
 * @param similarStayMinDays durée de séjour minimale, en jours, de {@code similarOffers} ; présent
 *                         seulement avec {@code similarOffers}
 * @param similarStayMaxDays durée de séjour maximale, en jours, de {@code similarOffers} ; présent
 *                         seulement avec {@code similarOffers}
 */
public record FlightSearchResponse(
        String originCode,
        String destinationCode,
        boolean roundTrip,
        String currency,
        List<FlightOfferDto> offers,
        String searchPath,
        Boolean nearbyDates,
        Integer nearbyDaysWindow,
        List<FlightOfferDto> similarOffers,
        Integer similarStayMinDays,
        Integer similarStayMaxDays) {

    /** Résultat aux dates demandées, sans repli. */
    public FlightSearchResponse(String originCode, String destinationCode, boolean roundTrip, String currency,
                                List<FlightOfferDto> offers, String searchPath) {
        this(originCode, destinationCode, roundTrip, currency, offers, searchPath, null, null);
    }

    /** Résultat sans séjours de durée proche. */
    public FlightSearchResponse(String originCode, String destinationCode, boolean roundTrip, String currency,
                                List<FlightOfferDto> offers, String searchPath, Boolean nearbyDates,
                                Integer nearbyDaysWindow) {
        this(originCode, destinationCode, roundTrip, currency, offers, searchPath, nearbyDates, nearbyDaysWindow,
                null, null, null);
    }
}
