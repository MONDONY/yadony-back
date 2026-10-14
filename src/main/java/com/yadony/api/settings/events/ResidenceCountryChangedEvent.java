package com.yadony.api.settings.events;

import java.util.UUID;

/**
 * Le pays de résidence d'un utilisateur vient de changer. Publié dans la transaction du
 * changement ({@code UserBusinessPrefsService.upsert}), après le contrôle du verrou pays.
 *
 * <p>Quand l'utilisateur porte un compte Stripe Connect encore en attente d'onboarding (non
 * verrouillant, voir {@code CountryLockService}), son identifiant est joint : {@code payments/}
 * le détache dans la même transaction (le changement de pays et le détachement sont
 * atomiques), puis tente de le fermer chez Stripe après commit.
 *
 * @param userId                 utilisateur concerné
 * @param previousCountry        pays avant le changement (ISO alpha-2 majuscules), ou null
 * @param newCountry             nouveau pays (ISO alpha-2 majuscules)
 * @param pendingStripeAccountId compte Connect en attente à détacher, ou null s'il n'y en a pas
 */
public record ResidenceCountryChangedEvent(UUID userId, String previousCountry, String newCountry,
                                           String pendingStripeAccountId) {
}
