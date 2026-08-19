package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.yadony.api.auth.UserEntity;

/**
 * Lot 4 (préparation Accounts v2, 2026-08-19/20) : seule la création de compte
 * Connect est isolée derrière cette interface. {@link PaymentService} ne sait
 * plus comment un compte est provisionné (Type.EXPRESS v1 aujourd'hui) — juste
 * qu'il obtient un identifiant de compte en retour.
 *
 * <p>Objectif explicite : quand la bascule vers l'API Accounts v2 sera décidée
 * (voir {@code docs/specs/2026-08-19-migration-accounts-v2.md}), elle consiste
 * à écrire une nouvelle implémentation de cette interface et changer le bean
 * injecté — jamais à réécrire {@link PaymentService}. Rien ne bascule dans ce
 * lot : {@link StripeExpressAccountProvisioner} reproduit exactement le
 * comportement v1 déjà en production, aucun changement fonctionnel.
 */
public interface ConnectAccountProvisioner {

    Account provision(UserEntity user) throws StripeException;
}
