package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.yadony.api.auth.UserEntity;

/**
 * Seul point de contact pour la création d'un compte Stripe Connect voyageur.
 * {@link PaymentService} ne sait pas comment le compte est provisionné : il reçoit
 * un identifiant {@code acct_...} et l'enregistre.
 *
 * <p>La méthode rend un {@code String} et non un objet {@code Account} : l'appelant
 * ne consomme que l'identifiant. Rendre le type v1 {@code com.stripe.model.Account}
 * imposerait, depuis une création v2, soit un {@code Account.retrieve} superflu,
 * soit la fabrication d'un objet v1 à partir d'un {@code v2.core.Account}, qui est
 * un type distinct.
 */
public interface ConnectAccountProvisioner {

    /**
     * @return l'identifiant du compte connecté créé ({@code acct_...})
     */
    String provision(UserEntity user) throws StripeException;
}
