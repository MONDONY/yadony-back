package com.yadony.api.matching.events;

import com.yadony.api.matching.BidNegotiationMessageKind;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un message vient d'être posté sur le fil de négociation d'un trajet.
 *
 * <p>Seul canal entre {@code matching/} et {@code notifications/} : le service de
 * négociation n'injecte aucun dispatcher, il publie cet événement et
 * {@code notifications/BidNegotiationEventsListener} décide quoi envoyer.
 *
 * @param recipientId la contrepartie à prévenir (jamais l'auteur)
 */
public record BidNegotiationMessagePostedEvent(
        UUID bidId,
        UUID announcementId,
        UUID authorId,
        UUID recipientId,
        BidNegotiationMessageKind kind,
        BigDecimal proposedGrossEur,
        int round
) {}
