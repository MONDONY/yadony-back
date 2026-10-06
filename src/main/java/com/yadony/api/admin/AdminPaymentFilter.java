package com.yadony.api.admin;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Filtres de la liste Transactions › Paiements, partagés par la liste, les totaux et l'export :
 * les trois doivent toujours montrer le même périmètre.
 *
 * @param rail           {@code STRIPE} ou {@code PAWAPAY} (filtre « Méthode »)
 * @param held           seulement les versements retenus (séquestre + {@code payout_held_at})
 * @param query          recherche libre : identifiant (paiement, colis, négociation), référence
 *                       Stripe ({@code pi_…}, {@code ch_…}) ou nom / pseudo d'une des parties
 * @param hideAbandoned  masque les checkouts jamais terminés (PENDING depuis plus de 24 h)
 */
public record AdminPaymentFilter(
        String status,
        LocalDateTime from,
        LocalDateTime to,
        String rail,
        String currency,
        boolean held,
        String query,
        boolean hideAbandoned
) {
    /** Normalise les paramètres HTTP : vide → absent, rail/devise/statut en majuscules. */
    public static AdminPaymentFilter of(String status, LocalDateTime from, LocalDateTime to, String method,
                                        String currency, Boolean held, String query, Boolean hideAbandoned) {
        return new AdminPaymentFilter(upper(status), from, to, upper(method), upper(currency),
                Boolean.TRUE.equals(held), blankToNull(query), Boolean.TRUE.equals(hideAbandoned));
    }

    private static String upper(String value) {
        String v = blankToNull(value);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
