package com.yadony.api.notifications;

/**
 * Types des notifications du supplément de prix. Aucun n'est critique (pas de SMS de repli :
 * décision du propriétaire du 19/10/2026) ; tous rangés en « paiements », réglés par
 * l'interrupteur des colis.
 */
public final class BidSupplementNotifications {

    public static final String REQUESTED = "BID_SUPPLEMENT_REQUESTED";
    public static final String PAID = "BID_SUPPLEMENT_PAID";
    public static final String DECLINED = "BID_SUPPLEMENT_DECLINED";
    public static final String EXPIRED = "BID_SUPPLEMENT_EXPIRED";
    public static final String WITHDRAWN = "BID_SUPPLEMENT_WITHDRAWN";
    public static final String REFUNDED = "BID_SUPPLEMENT_REFUNDED";
    public static final String PAID_OUT = "BID_SUPPLEMENT_PAID_OUT";

    private BidSupplementNotifications() {
    }
}
