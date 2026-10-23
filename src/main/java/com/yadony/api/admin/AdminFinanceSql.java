package com.yadony.api.admin;

/**
 * Fragments SQL natifs des listes finance de l'admin (constantes de compilation), sur PostgreSQL et sur H2 en
 * mode PostgreSQL. Même méthode que {@link AdminPaymentSql} : chaque vue, chaque nombre et chaque colonne
 * résolue est écrit UNE fois ici, la page et les nombres le partagent.
 *
 * <p><b>Mobile money.</b> Alias posés par {@link #MM_FROM} : {@code o} (opération pawaPay), {@code p} (son
 * paiement de colis) et le contexte de {@link AdminPaymentSql#PAYMENT_CONTEXT_JOIN} ({@code b}, {@code a},
 * {@code t}, {@code r}, {@code lb}, {@code la}), {@code su} (supplément de prix), {@code sb}/{@code sa} (colis
 * du supplément et son trajet), {@code u} (personne concernée). Paramètres nommés : {@code :view},
 * {@code :kind}, {@code :provider}, et pour la recherche {@code :t1} à {@code :t4}, {@code :idTerm},
 * {@code :refTerm}. Le téléphone n'entre dans aucun d'eux : {@code msisdn} est chiffré, {@code msisdn_masked}
 * n'est qu'un affichage.
 */
public final class AdminFinanceSql {

    private AdminFinanceSql() {
    }

    private static final String DEAD = AdminPaymentSql.OP_FAILED;
    private static final String OPEN = "('CREATED', 'ACCEPTED', 'PROCESSING', 'ENQUEUED', 'IN_RECONCILIATION')";

    /** Opération d'un remboursement ou d'une recharge du solde : la personne est son {@code user_id}. */
    private static final String WALLET_PURPOSE = "o.purpose IN ('WALLET_TOPUP', 'WALLET_REFUND')";
    /** Opération rattachée à un colis : paiement de colis ou supplément de prix. */
    private static final String PARCEL_OPERATION = "(o.payment_id IS NOT NULL OR o.purpose = 'BID_SUPPLEMENT')";

    /**
     * Personne concernée : le voyageur pour un versement (du paiement ou du supplément), l'expéditeur pour un
     * dépôt ou un remboursement, le titulaire du solde ({@code user_id}) pour une opération de solde.
     */
    public static final String MM_PERSON_ID = "(CASE"
            + " WHEN " + WALLET_PURPOSE + " THEN o.user_id"
            + " WHEN o.purpose = 'BID_SUPPLEMENT' AND o.kind = 'PAYOUT' THEN su.traveler_id"
            + " WHEN o.purpose = 'BID_SUPPLEMENT' THEN su.sender_id"
            + " WHEN o.payment_id IS NOT NULL AND o.kind = 'PAYOUT' THEN " + AdminPaymentSql.EFF_TRAVELER_ID
            + " WHEN o.payment_id IS NOT NULL THEN " + AdminPaymentSql.EFF_SENDER_ID
            + " ELSE o.user_id END)";

    /** Rôle de {@link #MM_PERSON_ID} : {@code TRAVELER}, {@code SENDER} ou {@code USER}. */
    public static final String MM_ROLE = "(CASE"
            + " WHEN " + WALLET_PURPOSE + " THEN 'USER'"
            + " WHEN " + PARCEL_OPERATION + " AND o.kind = 'PAYOUT' THEN 'TRAVELER'"
            + " WHEN " + PARCEL_OPERATION + " THEN 'SENDER'"
            + " ELSE 'USER' END)";

    /** Colis, suivi et villes : ceux du paiement (direct, lié au fil ou demande), sinon ceux du supplément. */
    public static final String MM_BID_ID = "COALESCE(" + AdminPaymentSql.EFF_BID_ID + ", sb.id)";
    public static final String MM_TRACKING = "COALESCE(" + AdminPaymentSql.EFF_TRACKING + ", sb.tracking_number)";
    public static final String MM_DEPARTURE = "COALESCE(" + AdminPaymentSql.EFF_DEPARTURE + ", sa.departure_city)";
    public static final String MM_ARRIVAL = "COALESCE(" + AdminPaymentSql.EFF_ARRIVAL + ", sa.arrival_city)";

    public static final String MM_FROM = " pawapay_operations o"
            + " LEFT JOIN payments p ON p.id = o.payment_id"
            + AdminPaymentSql.PAYMENT_CONTEXT_JOIN
            + " LEFT JOIN bid_supplements su ON su.id = o.bid_supplement_id"
            + " LEFT JOIN bids sb ON sb.id = su.bid_id"
            + " LEFT JOIN announcements sa ON sa.id = sb.announcement_id"
            + " LEFT JOIN users u ON u.id = " + MM_PERSON_ID + " ";

    /**
     * Demande de remboursement du solde servie par l'opération ({@code wallet_refund_request_items} la
     * référence par {@code pawapay_refund_id} ou {@code pawapay_payout_id}), ou null.
     */
    public static final String MM_WALLET_REFUND_REQUEST_ID = "(CASE WHEN o.purpose = 'WALLET_REFUND' THEN"
            + " (SELECT CAST(i.refund_request_id AS VARCHAR(36)) FROM wallet_refund_request_items i"
            + "  WHERE i.pawapay_refund_id = o.id OR i.pawapay_payout_id = o.id"
            + "  ORDER BY i.created_at, i.id LIMIT 1) END)";

    /** {@code o} est la dernière opération de son type pour son paiement (même règle que {@code findLatest}). */
    private static final String LATEST_OF_PAYMENT = "NOT EXISTS (SELECT 1 FROM pawapay_operations o3"
            + " WHERE o3.payment_id = o.payment_id AND o3.kind = o.kind AND o3.created_at > o.created_at)";

    /** Le dernier dépôt du paiement a abouti (garde de la relance de remboursement). */
    private static final String LATEST_DEPOSIT_COMPLETED = "EXISTS (SELECT 1 FROM pawapay_operations d"
            + " WHERE d.payment_id = o.payment_id AND d.kind = 'DEPOSIT' AND d.status = 'COMPLETED'"
            + " AND NOT EXISTS (SELECT 1 FROM pawapay_operations d2"
            + "     WHERE d2.payment_id = d.payment_id AND d2.kind = 'DEPOSIT' AND d2.created_at > d.created_at))";

    /**
     * Les gardes de {@code AdminPaymentController#retryMobileMoneyPayout} et {@code #retryMobileMoneyRefund} :
     * paiement du rail pawaPay, opération morte et dernière de son type ; versement d'un paiement
     * {@code RELEASED} dont le colis et le trajet sont connus, ou remboursement d'un paiement {@code REFUNDED}
     * ou {@code CANCELLED} dont le dernier dépôt a abouti. 1 si la relance serait acceptée, 0 sinon.
     */
    public static final String MM_RETRYABLE = "(CASE WHEN p.rail = 'PAWAPAY' AND o.status IN " + DEAD
            + " AND " + LATEST_OF_PAYMENT
            + " AND ((o.kind = 'PAYOUT' AND p.status = 'RELEASED' AND " + AdminPaymentSql.EFF_BID_ID + " IS NOT NULL"
            + "       AND " + AdminPaymentSql.EFF_ANNOUNCEMENT_ID + " IS NOT NULL)"
            + "   OR (o.kind = 'REFUND' AND p.status IN ('REFUNDED', 'CANCELLED') AND " + LATEST_DEPOSIT_COMPLETED + "))"
            + " THEN 1 ELSE 0 END)";

    /** Prédicat de chaque vue (hors {@code ALL}) : la liste et les nombres le partagent. */
    public static final String MM_FAILED_VIEW = "(o.status IN " + DEAD + ")";
    public static final String MM_OPEN_VIEW = "(o.status IN " + OPEN + ")";
    public static final String MM_DONE_VIEW = "(o.status = 'COMPLETED')";

    /** Vue rapide {@code :view} (nom de {@link AdminMobileMoneyView}). */
    public static final String MM_VIEW_PREDICATE = "(CAST(:view AS VARCHAR) = 'ALL'"
            + " OR (CAST(:view AS VARCHAR) = 'FAILED' AND " + MM_FAILED_VIEW + ")"
            + " OR (CAST(:view AS VARCHAR) = 'OPEN' AND " + MM_OPEN_VIEW + ")"
            + " OR (CAST(:view AS VARCHAR) = 'DONE' AND " + MM_DONE_VIEW + "))";

    /** Filtres facultatifs : type d'opération (nom exact) et fournisseur (insensible à la casse). */
    public static final String MM_FILTERS = "(CAST(:kind AS VARCHAR) IS NULL OR o.kind = CAST(:kind AS VARCHAR))"
            + " AND (CAST(:provider AS VARCHAR) IS NULL OR UPPER(o.provider) = UPPER(CAST(:provider AS VARCHAR)))";

    /**
     * Texte replié où cherchent les jetons : n° de suivi, prénom, nom et pseudo de la personne, villes du trajet,
     * fournisseur sans ses {@code _} (un jeton n'en contient jamais). Aucun téléphone.
     */
    private static final String MM_HAYSTACK = AdminBidSql.FOLD_OPEN + "CONCAT_WS(' ', " + MM_TRACKING + ","
            + " u.first_name, u.last_name, u.username, " + MM_DEPARTURE + ", " + MM_ARRIVAL + ","
            + " REPLACE(o.provider, '_', ''))" + AdminBidSql.FOLD_CLOSE;

    private static String token(String param) {
        return "(CAST(:" + param + " AS VARCHAR) IS NULL OR " + MM_HAYSTACK
                + " LIKE '%' || CAST(:" + param + " AS VARCHAR) || '%')";
    }

    private static final String ID_TERM = "LOWER(CAST(:idTerm AS VARCHAR))";

    /**
     * Recherche libre, en OU : chaque jeton non nul ({@code :t1} à {@code :t4}, repliés) se trouve dans le texte
     * replié ; ou {@code :idTerm} est l'identifiant exact de l'opération, de son paiement ou de son colis ; ou
     * {@code :refTerm} est l'identifiant fournisseur de l'opération (casse ignorée).
     */
    public static final String MM_SEARCH_PREDICATE = "(("
            + token("t1") + " AND " + token("t2") + " AND " + token("t3") + " AND " + token("t4") + ")"
            + " OR (CAST(:idTerm AS VARCHAR) IS NOT NULL AND ("
            + "     CAST(o.id AS VARCHAR) = " + ID_TERM
            + "     OR CAST(o.payment_id AS VARCHAR) = " + ID_TERM
            + "     OR CAST(" + MM_BID_ID + " AS VARCHAR) = " + ID_TERM + "))"
            + " OR LOWER(o.provider_transaction_id) = LOWER(CAST(:refTerm AS VARCHAR)))";

    /** Colonnes d'une ligne de la page (l'opération elle-même est relue par son identifiant). */
    public static final String MM_SELECT = "SELECT CAST(o.id AS VARCHAR(36)) AS \"operationId\","
            + " CAST(" + MM_PERSON_ID + " AS VARCHAR(36)) AS \"personId\","
            + " " + MM_ROLE + " AS \"role\","
            + " CAST(" + MM_BID_ID + " AS VARCHAR(36)) AS \"bidId\","
            + " " + MM_TRACKING + " AS \"trackingNumber\","
            + " " + MM_DEPARTURE + " AS \"departureCity\","
            + " " + MM_ARRIVAL + " AS \"arrivalCity\","
            + " " + MM_WALLET_REFUND_REQUEST_ID + " AS \"walletRefundRequestId\","
            + " " + MM_RETRYABLE + " AS \"retryable\" ";

    public static final String MM_ORDER_BY = " ORDER BY o.created_at DESC, o.id DESC";

    private static String sum(String predicate, String alias) {
        return "COALESCE(SUM(CASE WHEN " + predicate + " THEN 1 ELSE 0 END), 0) AS \"" + alias + "\"";
    }

    /**
     * Nombres par vue en un aller-retour, sur le {@code WHERE} de la liste sans {@code view}. {@code attention}
     * compte les opérations de {@link AdminPaymentSql#MM_FAILED_OPERATION} (échecs de versement ou de
     * remboursement d'un paiement, non remplacés).
     */
    public static final String MM_COUNTS_SELECT = "SELECT "
            + sum(MM_FAILED_VIEW, "failed") + ", "
            + sum(MM_OPEN_VIEW, "open") + ", "
            + sum(MM_DONE_VIEW, "done") + ", "
            + "COUNT(*) AS \"all\", "
            + sum(AdminPaymentSql.MM_FAILED_OPERATION, "attention") + " ";
}
