package com.yadony.api.notifications;

/**
 * Le transporteur SMS n'a pas envoyé le message pour une raison qui ne tient pas au numéro
 * saisi : compte Twilio suspendu ou identifiants refusés (401), route refusée pour ce couple
 * expéditeur et destinataire (21612), panne réseau. Levée seulement par
 * {@link SmsService#sendOrThrow}, réservé aux codes de connexion : l'appelant prévient
 * l'utilisateur au lieu de lui annoncer un « code envoyé » qui n'arrivera jamais
 * (Sentry YADONY-BACK-PROD-G, 08/11/2026).
 *
 * <p>Le message ne porte que la cause technique, jamais le numéro : il finit dans les logs et
 * dans Sentry.
 */
public class SmsDeliveryException extends RuntimeException {

    private final String deliveryFailure;

    public SmsDeliveryException(String deliveryFailure) {
        super("SMS non envoyé par le transporteur (" + deliveryFailure + ")");
        this.deliveryFailure = deliveryFailure;
    }

    /** Cause technique courte : « code 21612 », « HTTP 401 », nom de l'exception réseau. */
    public String getDeliveryFailure() {
        return deliveryFailure;
    }
}
