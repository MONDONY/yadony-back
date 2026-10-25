package com.yadony.api.payments.pawapay;

/**
 * À quoi sert une opération pawaPay : paiement de colis (lié à payments), recharge ou remboursement de
 * wallet (lié à users), supplément de prix à la remise (lié à bid_supplements, jamais à payments),
 * modification d'un colis accepté (lié à bid_amendments, jamais à payments).
 */
public enum PawapayOperationPurpose {
    BID_PAYMENT,
    WALLET_TOPUP,
    WALLET_REFUND,
    BID_SUPPLEMENT,
    BID_AMENDMENT
}
