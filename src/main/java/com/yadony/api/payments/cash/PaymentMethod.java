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
}
