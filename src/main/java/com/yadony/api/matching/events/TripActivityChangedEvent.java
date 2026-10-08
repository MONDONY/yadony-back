package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Un trajet entre ou sort de l'ensemble des trajets « actifs » du voyageur
 * (ACTIVE, FULL, IN_PROGRESS) sans passer par une création, une suppression ou une
 * annulation : dépublication, retrait ou restauration par la modération, clôture
 * automatique (départ sans colis, dernière livraison).
 *
 * <p>Sert à vider le cache {@code trips-summary}, dont l'indicateur {@code activeTrips}
 * restait sinon figé jusqu'au TTL Caffeine (FLUTTER-FC).
 */
public record TripActivityChangedEvent(UUID announcementId, UUID travelerId) {}
