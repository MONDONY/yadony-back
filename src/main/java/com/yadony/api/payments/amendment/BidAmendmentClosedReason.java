package com.yadony.api.payments.amendment;

/** Pourquoi une modification de colis a été close sans aboutir (colonne {@code closed_reason}). */
public enum BidAmendmentClosedReason {
    DECLINED_BY_TRAVELER,
    CANCELLED_BY_SENDER,
    EXPIRED,
    AUTHORIZATION_NOT_CONFIRMED,
    PAYMENT_NOT_RECEIVED,
    CAPTURE_FAILED,
    COMMISSION_NOT_CONFIRMED,
    COMMISSION_FAILED,
    NOT_BROUGHT,
    BID_ENDED
}
