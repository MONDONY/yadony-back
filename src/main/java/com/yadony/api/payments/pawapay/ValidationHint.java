package com.yadony.api.payments.pawapay;

/**
 * Comment un opérateur mobile money demande au payeur de valider une recharge : l'app adapte
 * son message d'attente en conséquence.
 */
public enum ValidationHint {
    /** L'opérateur envoie un SMS contenant un lien à ouvrir pour valider (seul cas constaté : Orange CI). */
    SMS_LINK,
    /** L'opérateur affiche une invite à saisir le code PIN sur le téléphone. */
    PIN_PROMPT,
    /** Opérateur inconnu ou non renseigné : message neutre. */
    GENERIC
}
