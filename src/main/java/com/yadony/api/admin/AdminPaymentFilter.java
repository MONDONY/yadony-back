package com.yadony.api.admin;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Filtres de la liste Transactions › Paiements, partagés par la liste, les totaux, les nombres et
 * l'export : les quatre doivent toujours montrer le même périmètre.
 *
 * @param rail           {@code STRIPE} ou {@code PAWAPAY} (filtre « Méthode »)
 * @param held           seulement les versements retenus (séquestre + {@code payout_held_at})
 * @param query          recherche libre : identifiant (paiement, colis, négociation), référence
 *                       Stripe ({@code pi_…}, {@code ch_…}) ou nom / pseudo d'une des parties
 * @param hideAbandoned  masque les checkouts jamais terminés (PENDING depuis plus de 24 h)
 * @param view           vue rapide ({@link AdminPaymentView#ALL} par défaut)
 * @param alert          seulement les paiements dont l'alerte la plus urgente est celle-ci
 * @param partyId        expéditeur OU voyageur du paiement
 * @param announcementId trajet du colis du paiement
 * @param bidId          colis direct ou lié à la négociation
 * @param amountMin      borne basse du montant en centimes (incluse)
 * @param amountMax      borne haute du montant en centimes (incluse)
 */
public record AdminPaymentFilter(
        String status,
        LocalDateTime from,
        LocalDateTime to,
        String rail,
        String currency,
        boolean held,
        String query,
        boolean hideAbandoned,
        AdminPaymentView view,
        AdminPaymentAlert alert,
        UUID partyId,
        UUID announcementId,
        UUID bidId,
        Long amountMin,
        Long amountMax
) {
    /** Constructeur à huit composantes : les filtres étendus prennent leur valeur par défaut. */
    public AdminPaymentFilter(String status, LocalDateTime from, LocalDateTime to, String rail, String currency,
                              boolean held, String query, boolean hideAbandoned) {
        this(status, from, to, rail, currency, held, query, hideAbandoned,
                AdminPaymentView.ALL, null, null, null, null, null, null);
    }

    /** Normalise les paramètres HTTP : vide → absent, rail/devise/statut en majuscules. */
    public static AdminPaymentFilter of(String status, LocalDateTime from, LocalDateTime to, String method,
                                        String currency, Boolean held, String query, Boolean hideAbandoned) {
        return new AdminPaymentFilter(upper(status), from, to, upper(method), upper(currency),
                Boolean.TRUE.equals(held), blankToNull(query), Boolean.TRUE.equals(hideAbandoned));
    }

    /** Variante complète : {@code view} et {@code alert} sont normalisés en majuscules, vide → défaut. */
    public static AdminPaymentFilter of(String status, LocalDateTime from, LocalDateTime to, String method,
                                        String currency, Boolean held, String query, Boolean hideAbandoned,
                                        String view, String alert, UUID partyId, UUID announcementId, UUID bidId,
                                        Long amountMin, Long amountMax) {
        String v = upper(view);
        String a = upper(alert);
        return new AdminPaymentFilter(upper(status), from, to, upper(method), upper(currency),
                Boolean.TRUE.equals(held), blankToNull(query), Boolean.TRUE.equals(hideAbandoned),
                v == null ? AdminPaymentView.ALL : AdminPaymentView.valueOf(v),
                a == null ? null : AdminPaymentAlert.valueOf(a),
                partyId, announcementId, bidId, amountMin, amountMax);
    }

    private static String upper(String value) {
        String v = blankToNull(value);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
