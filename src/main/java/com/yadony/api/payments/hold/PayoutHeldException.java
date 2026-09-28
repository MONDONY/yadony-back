package com.yadony.api.payments.hold;

/**
 * Versement refuse : le voyageur est gele ou le paiement est en litige, et l'appelant n'a pas
 * pose de derogation explicite.
 *
 * <p>Etend {@link IllegalStateException} : les appelants existants de
 * {@code MobileMoneyPayoutInitiator#release} traitent deja cette famille comme un echec qui
 * annule leur claim, le paiement reste donc ESCROW.
 */
public class PayoutHeldException extends IllegalStateException {

    public PayoutHeldException(String message) {
        super(message);
    }
}
