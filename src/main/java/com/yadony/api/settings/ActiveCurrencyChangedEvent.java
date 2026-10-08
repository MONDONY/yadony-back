package com.yadony.api.settings;

import java.util.UUID;

/**
 * La devise active d'un utilisateur vient de changer (FLUTTER-8F). Publié dans la
 * transaction du changement : les écouteurs synchrones qui convertissent des montants
 * stockés sans devise y participent, et un échec annule le changement.
 *
 * <p>Aucun solde n'est concerné : chaque portefeuille garde sa devise et son montant.
 *
 * @param userId           utilisateur concerné
 * @param previousCurrency devise active avant le changement (code ISO en majuscules)
 * @param newCurrency      nouvelle devise active (code ISO en majuscules)
 */
public record ActiveCurrencyChangedEvent(UUID userId, String previousCurrency, String newCurrency) {
}
