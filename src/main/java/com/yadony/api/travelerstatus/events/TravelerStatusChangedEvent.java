package com.yadony.api.travelerstatus.events;

import com.yadony.api.travelerstatus.TravelerStatus;

import java.util.UUID;

/**
 * Publié quand un voyageur déclare ou modifie son statut légal. Les autres paquets l'écoutent
 * (ex. éviction du cache de recherche des trajets, où la pastille du statut est affichée).
 * Ne porte ni le numéro d'immatriculation ni la raison sociale.
 */
public record TravelerStatusChangedEvent(UUID userId, TravelerStatus status) {
}
