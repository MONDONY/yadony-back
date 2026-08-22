package com.yadony.api.payments.dto;

import com.yadony.api.auth.StripeAccountStatus;

/**
 * @param connectAvailableInCountry faux quand Stripe ne couvre pas le pays de
 *        l'utilisateur : l'application masque alors l'activation du paiement par
 *        carte au lieu de laisser le voyageur la tenter et echouer.
 */
public record ConnectAccountResponse(
        String stripeAccountId,
        StripeAccountStatus stripeAccountStatus,
        boolean connectAvailableInCountry
) {}
