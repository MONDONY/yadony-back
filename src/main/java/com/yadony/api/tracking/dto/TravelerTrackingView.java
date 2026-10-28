package com.yadony.api.tracking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Suivi approximatif du voyageur tel qu'un lecteur (expéditeur ou page publique) le voit :
 * une ville proche et un pourcentage de trajet, jamais de coordonnées.
 *
 * @param status        statut calculé par le serveur
 * @param lastUpdatedAt heure du dernier échantillon visible ; absent sans échantillon
 * @param progressPct   0 à 100 ; absent quand la position est hors de la ligne départ → arrivée
 * @param nearestCity   ville proche du dernier échantillon visible
 * @param history       points successifs (villes consécutives identiques regroupées, le dernier est la
 *                      position actuelle) ; toujours vide pour la page publique
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TravelerTrackingView(
        TrackingStatus status,
        OffsetDateTime lastUpdatedAt,
        Integer progressPct,
        String nearestCity,
        List<HistoryPoint> history
) {

    public enum TrackingStatus { NOT_ACTIVATED, EN_ROUTE, ARRIVED, STALE }

    public record HistoryPoint(String city, OffsetDateTime at) {}
}
