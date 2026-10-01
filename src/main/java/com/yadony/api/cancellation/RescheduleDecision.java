package com.yadony.api.cancellation;

/** Réponse de l'expéditeur au report du trajet de son colis. */
public enum RescheduleDecision {
    /** Le colis reste sur le trajet, à la nouvelle date. */
    KEEP,
    /** L'expéditeur se retire sans frais : remboursement intégral et autres trajets proposés. */
    WITHDRAW
}
