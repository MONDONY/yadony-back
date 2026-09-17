package com.yadony.api.payments.pawapay;

/** À quoi sert une opération pawaPay : paiement de colis (lié à payments), recharge ou remboursement de wallet (lié à users). */
public enum PawapayOperationPurpose {
    BID_PAYMENT,
    WALLET_TOPUP,
    WALLET_REFUND
}
