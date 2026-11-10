package com.yadony.api.dac7;

/** Origine d'une ligne du calcul DAC7. */
public enum Dac7Source {
    /** Paiement carte ou mobile money versé au voyageur ({@code payments}, statut RELEASED). */
    PAYMENT,
    /** Colis réglé en espèces et livré ({@code bids}, sans paiement en ligne). */
    CASH,
    /** Supplément de prix à la remise ({@code bid_supplements}) : ajoute au montant, pas une opération de plus. */
    SUPPLEMENT,
    /** Modification d'un colis accepté ({@code bid_amendments}) : idem. */
    AMENDMENT
}
