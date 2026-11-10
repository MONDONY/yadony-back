package com.yadony.api.auth;

/**
 * Faut-il garder les informations fiscales DAC7 (NIF, pays d'émission, TVA) d'un compte qu'on supprime ? Port défini
 * ici, réalisé par le paquet {@code dac7} : la suppression de compte ne dépend pas de ses services (même modèle que
 * {@code matching.TravelerParcelsNetPort}). Évalué AVANT tout effacement.
 */
public interface TaxInfoRetentionPort {

    boolean mustRetainTaxInfo(UserEntity user);
}
