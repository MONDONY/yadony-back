package com.yadony.api.requests.entity;

public enum NegotiationThreadStatus {
    /** En cours de négociation (proposal / counter). */
    OPEN,
    /** Sender a accepté le prix. Le traveler doit lier (ou créer) un trajet. */
    AWAITING_TRIP,
    /** Traveler a lié un trajet. Le sender doit payer en escrow. */
    AWAITING_PAYMENT,
    /**
     * Accord en espèces conclu par l'expéditeur, en attente du règlement de la
     * commission Yadony par le voyageur. Rien n'est scellé à ce stade : la demande
     * reste ouverte, les offres concurrentes restent vivantes, aucun colis n'est
     * créé. Le premier voyageur qui règle emporte la demande ; passé le délai, le
     * thread expire.
     */
    AWAITING_COMMISSION,
    /** Paiement confirmé. Thread finalisé. Les threads concurrents passent à AUTO_REJECTED. */
    ACCEPTED,
    /** Rejet manuel par un participant. */
    REJECTED,
    /** Un participant a mis fin à la négociation avant paiement. */
    CANCELLED,
    /** Rejet auto : un thread concurrent sur la même demande a été ACCEPTED. */
    AUTO_REJECTED,
    /** Expiré faute d'activité. */
    EXPIRED;

    public boolean isActive() {
        return this == OPEN || this == AWAITING_TRIP || this == AWAITING_PAYMENT
            || this == AWAITING_COMMISSION;
    }
}
