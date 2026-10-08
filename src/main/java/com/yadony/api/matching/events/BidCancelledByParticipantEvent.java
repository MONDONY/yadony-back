package com.yadony.api.matching.events;

import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;

import java.util.UUID;

/**
 * Un participant (expéditeur ou voyageur) a annulé un colis à l'unité via
 * {@code BidService.cancelBid}. Publié en plus de {@link BidRejectedEvent}, qui porte
 * le remboursement et la notification : celui-ci porte ce que seule la transaction
 * d'annulation connaît, le statut du bid AVANT l'annulation, pour que
 * {@code cancellation.BidCancellationReputationListener} décide si l'annulation compte
 * dans la fiabilité de son auteur (seulement après acceptation par le voyageur).
 *
 * <p>Jamais publié par l'annulation d'un trajet entier : celle-ci compte une seule
 * annulation pour le voyageur, quel que soit le nombre de colis emportés.
 *
 * @param byTraveler {@code true} si le voyageur annule, {@code false} si c'est l'expéditeur
 */
public record BidCancelledByParticipantEvent(
        UUID bidId,
        UUID announcementId,
        UUID actorId,
        boolean byTraveler,
        BidStatus previousStatus,
        PaymentMethod paymentMethod
) {}
