package com.yadony.api.payments.supplement.dto;

/**
 * Issue d'une décision admin.
 *
 * @param acted vrai si le versement ou le remboursement est parti (ou a été constaté) pendant
 *              l'appel ; faux s'il est retenu (retenue du voyageur, compte Connect incomplet,
 *              charge contestée, opération déjà en cours) : une alerte {@code SUPP_*} en donne la
 *              raison, le statut du supplément est inchangé ou en attente.
 */
public record AdminSupplementDecisionResponse(boolean acted, AdminSupplementRow supplement) {
}
