package com.yadony.api.auth;

import com.yadony.api.travelerstatus.TravelerStatus;

import java.util.Optional;

/**
 * Faut-il garder les informations fiscales DAC7 (NIF, pays d'émission, TVA) d'un compte qu'on supprime ? Port défini
 * ici, réalisé par le paquet {@code dac7} : la suppression de compte ne dépend pas de ses services (même modèle que
 * {@code matching.TravelerParcelsNetPort}). Évalué AVANT tout effacement.
 *
 * <p>Contrat : ne lève jamais et n'altère jamais la transaction de l'appelant (la réalisation lit dans sa propre
 * transaction et se replie sur « conserver » en cas d'erreur) ; la suppression ne doit jamais dépendre de DAC7.
 */
public interface TaxInfoRetentionPort {

    /** Vide : tout effacer (comme avant DAC7). Présent : garder le NIF et figer la résidence fournie. */
    Optional<Retention> retention(UserEntity user);

    /** Pays de résidence DAC7 et statut à figer sur le compte supprimé (purgés avec le NIF). */
    record Retention(String residenceCountry, TravelerStatus sellerStatus) {
    }
}
