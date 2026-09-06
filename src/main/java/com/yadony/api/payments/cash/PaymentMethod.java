package com.yadony.api.payments.cash;

public enum PaymentMethod {
    STRIPE,
    CASH,
    /** Legacy : bids et demandes historiques. Plus jamais proposé ni traité. */
    WAVE,
    /** Legacy : idem. */
    ORANGE_MONEY,
    /** Rail pawaPay : l'opérateur réel (Orange, Wave, MTN…) vit sur l'opération, prédit depuis le numéro. */
    MOBILE_MONEY;

    /**
     * Modes où l'expéditeur paie après l'acceptation, dans l'application, et reçoit donc
     * une notification « Payez votre envoi » à la place du push générique « Demande
     * acceptée ! » (cf. {@code BidAcceptedEvent#isMobileMoney} et
     * {@code NotificationDispatcher#onBidAccepted}). Les valeurs legacy ne déclenchent
     * plus rien : un bid WAVE historique retrouve le push générique.
     */
    public boolean isMobileMoney() {
        return this == MOBILE_MONEY;
    }

    /**
     * Seule la carte passe par l'escrow Stripe : un bid de ce mode naît {@code AWAITING_PAYMENT}
     * et n'est acceptable qu'une fois {@code PAYMENT_ESCROWED}. Tous les autres modes (espèces,
     * mobile money pawaPay, legacy) naissent {@code PENDING} et se refusent depuis ce statut —
     * un prédicat plutôt qu'une liste, pour qu'un mode ajouté à cette énumération tombe du bon
     * côté sans qu'aucune liste écrite à la main ne l'oublie.
     */
    public boolean isCardEscrow() {
        return this == STRIPE;
    }
}
