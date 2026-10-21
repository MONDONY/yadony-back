package com.yadony.api.common;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Résumé d'une erreur de client HTTP sûr à journaliser.
 *
 * <p>Le message d'une {@link RestClientException} reprend l'URI complète de la requête
 * ({@code I/O error on GET request for "https://...?key=..."}). Quand un secret voyage dans
 * l'URL (jeton du bot Telegram, clé Google Geocoding), {@code e.getMessage()} le recopie dans
 * les journaux et, au-delà du niveau warn, dans Sentry. On ne garde donc que le statut HTTP
 * ou, à défaut, le type de l'erreur.
 */
public final class HttpErrorSummary {

    private HttpErrorSummary() {}

    public static String of(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        Throwable cause = e.getMostSpecificCause();
        return cause != e
                ? e.getClass().getSimpleName() + " (" + cause.getClass().getSimpleName() + ")"
                : e.getClass().getSimpleName();
    }
}
