package com.yadony.api.common.stripe;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.net.HttpURLConnectionClient;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;

import java.net.ConnectException;
import java.net.SocketException;
import java.time.Duration;

/**
 * Client HTTP Stripe qui relance aussi une connexion coupée en cours de route
 * (« Connection reset by peer », YADONY-BACK-STAGING-17).
 *
 * <p>stripe-java ne relance de lui-même que les {@link ConnectException} et les
 * {@code SocketTimeoutException} (puis les 409 et {@code Stripe-Should-Retry}) : une
 * {@link SocketException} de réinitialisation, la panne vue en staging, remonte au premier
 * essai même avec {@code maxNetworkRetries > 0}. Ce client la relance dans la même limite
 * ({@code maxNetworkRetries} de la requête).
 *
 * <p>Sûr pour un POST d'argent : la relance renvoie le <b>même</b> {@link StripeRequest},
 * donc les mêmes en-têtes et la même {@code Idempotency-Key} (la clé explicite de l'appelant,
 * sinon celle que le SDK génère une fois par requête). Si Stripe avait traité la première
 * tentative avant la coupure, il rend le résultat déjà obtenu au lieu de recommencer.
 */
public class ConnectionResetRetryingHttpClient extends HttpURLConnectionClient {

    private static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(500);

    private final Duration baseDelay;

    public ConnectionResetRetryingHttpClient() {
        this(DEFAULT_BASE_DELAY);
    }

    ConnectionResetRetryingHttpClient(Duration baseDelay) {
        this.baseDelay = baseDelay;
    }

    @Override
    public StripeResponse requestWithRetries(StripeRequest request) throws StripeException {
        int maxRetries = maxRetries(request);
        for (int retry = 0; ; retry++) {
            try {
                return super.requestWithRetries(request);
            } catch (ApiConnectionException e) {
                if (retry >= maxRetries || !isConnectionReset(e)) {
                    throw e;
                }
                if (!pause(retry)) {
                    throw e;
                }
            }
        }
    }

    /**
     * Coupure d'une connexion établie (reset, broken pipe, fin de flux inattendue). Une
     * {@link ConnectException} est exclue : le SDK la relance déjà lui-même.
     */
    static boolean isConnectionReset(ApiConnectionException e) {
        Throwable cause = e.getCause();
        return cause instanceof SocketException && !(cause instanceof ConnectException);
    }

    private static int maxRetries(StripeRequest request) {
        Integer configured = request.options() != null ? request.options().getMaxNetworkRetries() : null;
        return configured != null ? Math.max(0, configured) : 0;
    }

    /** Attente exponentielle bornée ; faux si le thread est interrompu (on n'insiste pas). */
    private boolean pause(int retry) {
        long millis = Math.min(baseDelay.toMillis() << Math.min(retry, 4),
                maxNetworkRetriesDelay.toMillis());
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
