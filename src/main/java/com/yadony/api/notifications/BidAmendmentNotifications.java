package com.yadony.api.notifications;

/**
 * Types des notifications de la modification d'un colis accepté. Aucun n'est critique (pas de SMS de
 * repli, même décision que pour le supplément) ; tous rangés en « paiements », réglés par les
 * interrupteurs des colis et des trajets.
 */
public final class BidAmendmentNotifications {

    public static final String REQUESTED = "BID_AMENDMENT_REQUESTED";
    public static final String REMINDER = "BID_AMENDMENT_REMINDER";
    public static final String CANCELLED = "BID_AMENDMENT_CANCELLED";
    public static final String ACCEPTED = "BID_AMENDMENT_ACCEPTED";
    public static final String DECLINED = "BID_AMENDMENT_DECLINED";
    public static final String EXPIRED = "BID_AMENDMENT_EXPIRED";
    public static final String NOT_BROUGHT = "BID_AMENDMENT_NOT_BROUGHT";
    public static final String REFUNDED = "BID_AMENDMENT_REFUNDED";

    private BidAmendmentNotifications() {
    }
}
