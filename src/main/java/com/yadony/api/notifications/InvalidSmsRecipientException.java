package com.yadony.api.notifications;

/**
 * Le transporteur SMS a refusé le numéro destinataire lui-même (Twilio 21211 « Invalid 'To'
 * Phone Number », 21614 « not a valid mobile number »). C'est une erreur de saisie de
 * l'utilisateur, pas un incident transporteur : l'appelant la remonte au client au lieu de
 * lui annoncer un « code envoyé » qui n'arrivera jamais.
 *
 * <p>Le message ne porte que le code d'erreur, jamais le numéro : il finit dans les logs et
 * dans Sentry.
 */
public class InvalidSmsRecipientException extends RuntimeException {

    private final int carrierErrorCode;

    public InvalidSmsRecipientException(int carrierErrorCode) {
        super("Numéro destinataire refusé par le transporteur SMS (code " + carrierErrorCode + ")");
        this.carrierErrorCode = carrierErrorCode;
    }

    public int getCarrierErrorCode() {
        return carrierErrorCode;
    }
}
