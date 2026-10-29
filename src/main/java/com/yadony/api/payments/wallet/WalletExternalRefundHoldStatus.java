package com.yadony.api.payments.wallet;

/** État d'un gel posé sur une devise après un remboursement Stripe fait hors de l'app. */
public enum WalletExternalRefundHoldStatus {
    /** Devise gelée : aucune dépense, aucun remboursement, aucun crédit admin. */
    OPEN,
    /** Gel levé par un admin, après régularisation du solde. */
    RELEASED
}
