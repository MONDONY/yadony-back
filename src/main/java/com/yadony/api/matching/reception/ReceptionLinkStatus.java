package com.yadony.api.matching.reception;

/** Réponse du destinataire à un colis rattaché à son compte. */
public enum ReceptionLinkStatus {
    /** Rattaché, le destinataire n'a pas encore répondu. */
    PENDING,
    /** Le destinataire confirme que le colis est pour lui : il le suit dans l'app. */
    CONFIRMED,
    /** Le titulaire du numéro indique que le colis n'est pas pour lui. */
    DECLINED;

    /**
     * Le voyageur ne voit plus le nom ni le numéro du destinataire : celui-ci a refusé le
     * colis ou s'en est retiré. Seule règle de masquage côté voyageur, lue par toutes les
     * réponses qui lui montrent le destinataire ({@code BidService.toResponse},
     * historique des scans du trajet). L'expéditeur, qui a saisi ces données, les voit
     * toujours.
     */
    public boolean hidesRecipientFromTraveler() {
        return this == DECLINED;
    }
}
