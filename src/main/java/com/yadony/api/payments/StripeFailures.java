package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

/**
 * Classement des erreurs Stripe, partagé par tout versement qui doit décider s'il peut revenir en arrière
 * (versement principal {@code StripeReleaseExecutor}, versement d'une modification de colis).
 */
public final class StripeFailures {

    private StripeFailures() {
    }

    /**
     * Refus certain de Stripe (rien n'a été créé) : requête invalide (hors limite de débit), carte, authentification,
     * permission. Tout le reste (réseau, 5xx, limite de débit, clé d'idempotence en cours ou réutilisée) est incertain
     * ou transitoire : on rejoue, on ne revient jamais en arrière. Les issues inconnues sont écartées d'abord :
     * {@link RateLimitException} héritait d'{@link InvalidRequestException} dans d'anciennes versions de stripe-java
     * (plus en 33.x), une montée ou une descente de version ne doit pas la reclasser en refus.
     */
    public static boolean definitiveRefusal(StripeException e) {
        if (e instanceof RateLimitException || e instanceof IdempotencyException
                || e instanceof ApiConnectionException || e instanceof ApiException) {
            return false;
        }
        return e instanceof InvalidRequestException || e instanceof CardException
                || e instanceof AuthenticationException || e instanceof PermissionException;
    }
}
