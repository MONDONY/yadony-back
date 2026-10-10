package com.yadony.api.common.stripe;

import com.stripe.exception.ApiConnectionException;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/**
 * Stripe injoignable : l'appel n'a pas abouti pour une cause réseau (connexion refusée ou
 * réinitialisée, délai dépassé), après les relances du SDK ({@code stripe.max-network-retries}).
 *
 * <p>Distinct d'un refus de Stripe (carte, compte, paramètres) : rien n'a été décidé, le même
 * geste réessayé un peu plus tard aboutit. Les chemins de paiement exposés à l'app répondent
 * donc un 502 {@code stripe-unavailable} plutôt qu'une 500 générique (YADONY-BACK-STAGING-17).
 */
public final class StripeUnavailable {

    public static final String CODE = "stripe-unavailable";

    private static final int MAX_CAUSE_DEPTH = 16;

    private StripeUnavailable() {
    }

    /** Vrai si {@code error}, ou l'une de ses causes, est une erreur réseau du SDK Stripe. */
    public static boolean isNetworkFailure(Throwable error) {
        // Profondeur bornée : une chaîne de causes cyclique ne boucle jamais.
        Throwable t = error;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof ApiConnectionException) {
                return true;
            }
        }
        return false;
    }

    /** Problème RFC 7807 {@code stripe-unavailable} (502), rendu par le handler des erreurs métier. */
    public static YadonyBusinessException exception() {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, CODE, "Stripe Unavailable",
                "Le service de paiement est momentanément injoignable, réessayez.");
    }
}
