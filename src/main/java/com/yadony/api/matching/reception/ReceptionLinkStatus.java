package com.yadony.api.matching.reception;

/** Réponse du destinataire à un colis rattaché à son compte. */
public enum ReceptionLinkStatus {
    /** Rattaché, le destinataire n'a pas encore répondu. */
    PENDING,
    /** Le destinataire confirme que le colis est pour lui : il le suit dans l'app. */
    CONFIRMED,
    /** Le titulaire du numéro indique que le colis n'est pas pour lui. */
    DECLINED
}
