package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Un expéditeur vient d'envoyer la description d'un colis : demande sur un trajet (espèces ou
 * mobile money), paiement par carte, ou proposition sur un trajet négociable. Lu après le commit
 * par {@code signalements/} pour la détection automatique d'envoi d'argent. La copie de la
 * description d'une demande d'expéditeur dans un bid ne le publie pas : la demande a déjà été
 * examinée.
 */
public record BidDescriptionSubmittedEvent(UUID bidId, UUID senderId, String description) {
}
