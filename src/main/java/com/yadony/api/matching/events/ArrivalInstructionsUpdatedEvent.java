package com.yadony.api.matching.events;

import java.util.List;
import java.util.UUID;

/**
 * Publié par {@code AnnouncementService#updateArrivalInstructions} quand le voyageur
 * modifie ses instructions de retrait après l'arrivée. {@code bidIds} : les colis
 * ARRIVED du trajet, ceux dont le destinataire attend encore le retrait.
 */
public record ArrivalInstructionsUpdatedEvent(UUID announcementId, List<UUID> bidIds) {}
