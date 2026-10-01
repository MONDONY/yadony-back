package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * L'expéditeur a remplacé le numéro du destinataire d'un colis (lot 3A). Publié dans
 * la transaction du changement ; les écouteurs agissent après validation :
 * rattachement du nouveau numéro, notification de l'ancien destinataire et du voyageur.
 *
 * @param bidId                   colis concerné
 * @param previousRecipientUserId ancien destinataire à prévenir (lien PENDING ou
 *                                CONFIRMED retiré), {@code null} s'il n'y a personne
 * @param travelerId              voyageur du colis, {@code null} si introuvable
 */
public record BidRecipientChangedEvent(UUID bidId, UUID previousRecipientUserId, UUID travelerId) {}
