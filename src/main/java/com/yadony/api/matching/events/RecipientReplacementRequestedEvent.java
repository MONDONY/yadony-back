package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Le destinataire d'un colis l'a refusé (ou s'en est retiré) et le voyageur demande à
 * l'expéditeur d'en désigner un autre. Publié dans la transaction de la demande ;
 * l'expéditeur est notifié après validation.
 *
 * @param bidId    colis concerné
 * @param senderId expéditeur à prévenir
 */
public record RecipientReplacementRequestedEvent(UUID bidId, UUID senderId) {}
