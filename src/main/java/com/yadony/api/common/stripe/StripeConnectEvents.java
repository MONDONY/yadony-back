package com.yadony.api.common.stripe;

import com.stripe.model.Event;

import java.util.Set;

/**
 * Événements qu'un compte connecté (voyageur) a le droit d'envoyer. Le modèle est « separate
 * charges and transfers » : tout paiement, remboursement ou litige naît sur le compte
 * plateforme, jamais sur un compte connecté. Un {@code payment_intent.succeeded} venu d'un
 * compte connecté ne peut donc être qu'un paiement encaissé par ce voyageur pour lui-même, et
 * ne doit surtout pas créditer un solde yadony à partir de ses métadonnées.
 */
public final class StripeConnectEvents {

    static final Set<String> ACCOUNT_TYPES = Set.of(
            "account.updated",
            "account.application.deauthorized",
            "capability.updated");

    private StripeConnectEvents() {
    }

    /** L'événement provient-il d'un compte connecté ({@code event.account} renseigné) ? */
    public static boolean fromConnectedAccount(Event event) {
        return event.getAccount() != null && !event.getAccount().isBlank();
    }

    /** Type attendu d'un compte connecté : vie du compte et virements vers sa banque. */
    public static boolean isConnectedAccountType(String type) {
        return type != null && (ACCOUNT_TYPES.contains(type) || type.startsWith("payout."));
    }
}
