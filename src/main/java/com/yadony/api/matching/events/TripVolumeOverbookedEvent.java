package com.yadony.api.matching.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un fil déjà payé a été matérialisé sur un trajet vendu au m³ qui n'avait plus assez de volume : le colis n'a pris que
 * ce qui restait ({@code takenM3} sur {@code volumeM3}), le trajet porte plus que son volume proposé. Publié par
 * {@code ThreadAcceptedBidListener} ; l'équipe est alertée (aucun argent ne bouge ici).
 *
 * @param announcementId trajet surréservé
 * @param bidId          colis matérialisé
 * @param volumeM3       volume du colis
 * @param takenM3        volume réellement pris sur le trajet
 */
public record TripVolumeOverbookedEvent(UUID announcementId, UUID bidId, BigDecimal volumeM3, BigDecimal takenM3) {}
