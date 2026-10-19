package com.yadony.api.payments.supplement.events;

import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementStatus;

import java.util.UUID;

/**
 * Demande close sans paiement (refus, retrait, expiration, colis terminé). Les rails écoutent ce
 * type commun pour défaire ce qui reste ouvert chez eux : PaymentIntent à annuler (carte),
 * commission à rendre (espèces). {@code previousStatus} dit d'où vient la demande.
 */
public sealed interface BidSupplementClosedEvent
        permits BidSupplementDeclinedEvent, BidSupplementWithdrawnEvent, BidSupplementExpiredEvent,
                BidSupplementCancelledEvent {

    UUID supplementId();

    UUID bidId();

    UUID senderId();

    UUID travelerId();

    BidSupplementRail rail();

    BidSupplementStatus previousStatus();
}
