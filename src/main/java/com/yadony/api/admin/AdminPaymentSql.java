package com.yadony.api.admin;

/**
 * Fragments SQL natifs de la liste des paiements de l'admin (constantes de compilation), sur
 * PostgreSQL et sur H2 en mode PostgreSQL. Même méthode que {@link AdminBidSql} : l'alerte, les
 * vues, le tri et les colonnes « effectives » sont écrits UNE fois ici, la liste, les nombres,
 * l'export et la carte {@code escrowJ48} les réutilisent.
 *
 * <p>Alias posés par {@link #FROM_JOIN} : {@code p} (payments), {@code b} (colis direct),
 * {@code lb} (colis lié au fil), {@code a}/{@code la} (trajets de {@code b}/{@code lb}), {@code t}
 * (fil de négociation), {@code r} (demande d'envoi), {@code s} (expéditeur effectif), {@code tr}
 * (voyageur effectif).
 *
 * <p>Paramètres nommés : {@code :now} (UTC), {@code :view}, {@code :alert}, {@code :abandonedBefore}, et pour
 * la recherche {@code :t1} à {@code :t4}, {@code :amountCents}, {@code :idTerm}, {@code :refTerm}. Les
 * paramètres facultatifs passent par {@code CAST(:p AS …)}.
 */
public final class AdminPaymentSql {

    private AdminPaymentSql() {
    }

    /** Un séquestre plus ancien est une alerte (identique à {@code AdminMetricsService.countEscrowJ48}). */
    public static final int ESCROW_TIMEOUT_HOURS = 48;
    /** Fenêtre de la vue « versés récemment ». */
    public static final int RELEASED_RECENT_HOURS = 72;
    /** Un checkout PENDING plus ancien est abandonné. */
    public static final int ABANDONED_AFTER_HOURS = 24;

    private static final String NOW = "CAST(:now AS TIMESTAMP)";

    /** Colis, expéditeur, voyageur, villes et suivi effectifs d'un paiement (direct, lié au fil ou demande). */
    public static final String EFF_BID_ID = "COALESCE(b.id, lb.id)";
    public static final String EFF_TRACKING = "COALESCE(b.tracking_number, lb.tracking_number)";
    public static final String EFF_ANNOUNCEMENT_ID = "COALESCE(a.id, la.id)";
    public static final String EFF_SENDER_ID = "COALESCE(b.sender_id, lb.sender_id, r.sender_id)";
    public static final String EFF_TRAVELER_ID = "COALESCE(a.traveler_id, la.traveler_id, t.traveler_id)";
    public static final String EFF_DEPARTURE = "COALESCE(a.departure_city, la.departure_city, r.departure_city)";
    public static final String EFF_ARRIVAL = "COALESCE(a.arrival_city, la.arrival_city, r.arrival_city)";

    public static final String FROM_JOIN = " payments p"
            + " LEFT JOIN bids b ON b.id = p.bid_id"
            + " LEFT JOIN announcements a ON a.id = b.announcement_id"
            + " LEFT JOIN negotiation_threads t ON t.id = p.negotiation_thread_id"
            + " LEFT JOIN package_requests r ON r.id = t.package_request_id"
            + " LEFT JOIN bids lb ON p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL"
            + "                   AND lb.linked_negotiation_thread_id = p.negotiation_thread_id"
            + " LEFT JOIN announcements la ON la.id = lb.announcement_id"
            + " LEFT JOIN users s ON s.id = " + EFF_SENDER_ID
            + " LEFT JOIN users tr ON tr.id = " + EFF_TRAVELER_ID + " ";

    private static final String OP_FAILED = "('FAILED', 'SUBMIT_REJECTED')";

    public static final String HELD_PREDICATE = "(p.status = 'ESCROW' AND p.payout_held_at IS NOT NULL)";

    public static final String CHARGEBACK_OPEN_PREDICATE = "EXISTS (SELECT 1 FROM chargebacks c"
            + " WHERE c.status = 'OPEN' AND c.deleted_at IS NULL"
            + " AND (c.payment_id = p.id OR (c.payment_id IS NULL AND c.bid_id = " + EFF_BID_ID + ")))";

    /** Versement ou remboursement mobile money en échec, sans opération plus récente non échouée du même type. */
    public static final String MM_FAILED_PREDICATE = "EXISTS (SELECT 1 FROM pawapay_operations o"
            + " WHERE o.payment_id = p.id AND o.kind IN ('PAYOUT', 'REFUND') AND o.status IN " + OP_FAILED
            + " AND NOT EXISTS (SELECT 1 FROM pawapay_operations o2"
            + "     WHERE o2.payment_id = p.id AND o2.kind = o.kind AND o2.created_at > o.created_at"
            + "     AND o2.status NOT IN " + OP_FAILED + "))";

    public static final String ESCROW_J48_PREDICATE = "(p.status = 'ESCROW'"
            + " AND p.created_at < " + NOW + " - INTERVAL '" + ESCROW_TIMEOUT_HOURS + "' HOUR)";

    /**
     * Rang d'alerte EXCLUSIF du paiement (étiquette de ligne, tri, vue {@code ATTENTION}), le plus urgent
     * l'emporte : 1 versement retenu, 2 litige bancaire
     * ouvert, 3 mobile money en échec, 4 séquestre de plus de {@value #ESCROW_TIMEOUT_HOURS} h ; 0 sinon.
     * Construit à partir des prédicats bruts ci-dessus, qui restent la seule définition de chaque alerte.
     */
    public static final String ALERT_RANK = "(CASE"
            + " WHEN " + HELD_PREDICATE + " THEN 1"
            + " WHEN " + CHARGEBACK_OPEN_PREDICATE + " THEN 2"
            + " WHEN " + MM_FAILED_PREDICATE + " THEN 3"
            + " WHEN " + ESCROW_J48_PREDICATE + " THEN 4"
            + " ELSE 0 END)";

    /**
     * Filtre {@code :alert} (nom de {@link AdminPaymentAlert}, ou null) sur le prédicat BRUT de l'alerte,
     * non exclusif : un séquestre retenu de plus de 48 h est aussi {@code ESCROW_J48}.
     */
    public static final String ALERT_FILTER = "(CAST(:alert AS VARCHAR) IS NULL"
            + " OR (:alert = 'HELD' AND " + HELD_PREDICATE + ")"
            + " OR (:alert = 'CHARGEBACK_OPEN' AND " + CHARGEBACK_OPEN_PREDICATE + ")"
            + " OR (:alert = 'MM_FAILED' AND " + MM_FAILED_PREDICATE + ")"
            + " OR (:alert = 'ESCROW_J48' AND " + ESCROW_J48_PREDICATE + "))";

    /** Prédicat de chaque vue (hors {@code ALL}) : seule définition, la liste et les nombres la partagent. */
    public static final String ATTENTION_VIEW = "(" + ALERT_RANK + " > 0)";
    public static final String ESCROW_VIEW = "(p.status = 'ESCROW')";
    public static final String RELEASED_RECENT_VIEW = "(p.status = 'RELEASED'"
            + " AND COALESCE(p.escrow_released_at, p.created_at) >= " + NOW
            + " - INTERVAL '" + RELEASED_RECENT_HOURS + "' HOUR)";
    public static final String REFUNDED_VIEW = "(p.status = 'REFUNDED')";
    public static final String FAILED_VIEW = "(p.status IN ('FAILED', 'CANCELLED'))";

    /** Vue rapide {@code :view} (nom de {@link AdminPaymentView}). */
    public static final String VIEW_PREDICATE = "(:view = 'ALL'"
            + " OR (:view = 'ATTENTION' AND " + ATTENTION_VIEW + ")"
            + " OR (:view = 'ESCROW' AND " + ESCROW_VIEW + ")"
            + " OR (:view = 'RELEASED_RECENT' AND " + RELEASED_RECENT_VIEW + ")"
            + " OR (:view = 'REFUNDED' AND " + REFUNDED_VIEW + ")"
            + " OR (:view = 'FAILED' AND " + FAILED_VIEW + "))";

    /** Vue « À surveiller » : rang croissant puis plus ancien d'abord ; sinon plus récent d'abord. */
    public static final String ORDER_BY = "CASE WHEN :view = 'ATTENTION' THEN " + ALERT_RANK + " END ASC,"
            + " CASE WHEN :view = 'ATTENTION' THEN p.created_at END ASC,"
            + " CASE WHEN :view = 'ATTENTION' THEN p.id END ASC,"
            + " p.created_at DESC, p.id DESC";

    /**
     * Texte replié ({@link AdminBidSql#FOLD_OPEN}, même règle que {@link AdminSearchTerms#fold}) où cherchent
     * les jetons : n° de suivi, prénom, nom et pseudo de l'expéditeur et du voyageur effectifs, villes de
     * départ et d'arrivée, séparés par une espace (un jeton ne chevauche pas deux champs). Aucun téléphone
     * n'y entre : celui du mobile money est chiffré et seulement affiché masqué.
     */
    private static final String HAYSTACK = AdminBidSql.FOLD_OPEN + "CONCAT_WS(' ', " + EFF_TRACKING + ","
            + " s.first_name, s.last_name, s.username, tr.first_name, tr.last_name, tr.username, "
            + EFF_DEPARTURE + ", " + EFF_ARRIVAL + ")" + AdminBidSql.FOLD_CLOSE;

    private static String token(String param) {
        return "(CAST(:" + param + " AS VARCHAR) IS NULL OR " + HAYSTACK
                + " LIKE '%' || CAST(:" + param + " AS VARCHAR) || '%')";
    }

    private static final String ID_TERM = "LOWER(CAST(:idTerm AS VARCHAR))";

    /**
     * Recherche libre, en OU : chaque jeton non nul ({@code :t1} à {@code :t4}, déjà repliés, sans {@code %}
     * ni {@code _}) se trouve dans {@link #HAYSTACK} ; ou {@code :amountCents} est le montant du paiement ;
     * ou {@code :idTerm} est l'identifiant exact du paiement, de son colis (direct ou lié au fil), de son fil
     * de négociation, du trajet du colis, d'une de ses opérations pawaPay ou d'un de ses litiges bancaires
     * (par paiement, ou par colis quand le litige n'a pas de paiement) ; ou {@code :refTerm} est sa
     * référence Stripe ({@code pi_…}, {@code ch_…}) ou l'identifiant fournisseur d'une de ses opérations
     * pawaPay. Les UUID se comparent en texte minuscule (PostgreSQL et H2).
     */
    public static final String SEARCH_PREDICATE = "(("
            + token("t1") + " AND " + token("t2") + " AND " + token("t3") + " AND " + token("t4") + ")"
            + " OR (CAST(:amountCents AS BIGINT) IS NOT NULL AND ROUND(p.amount * 100) = CAST(:amountCents AS BIGINT))"
            + " OR (CAST(:idTerm AS VARCHAR) IS NOT NULL AND ("
            + "     CAST(p.id AS VARCHAR) = " + ID_TERM
            + "     OR CAST(" + EFF_BID_ID + " AS VARCHAR) = " + ID_TERM
            + "     OR CAST(p.negotiation_thread_id AS VARCHAR) = " + ID_TERM
            + "     OR CAST(" + EFF_ANNOUNCEMENT_ID + " AS VARCHAR) = " + ID_TERM
            + "     OR EXISTS (SELECT 1 FROM pawapay_operations o WHERE o.payment_id = p.id"
            + "                AND CAST(o.id AS VARCHAR) = " + ID_TERM + ")"
            + "     OR EXISTS (SELECT 1 FROM chargebacks c WHERE c.deleted_at IS NULL"
            + "                AND (c.payment_id = p.id OR (c.payment_id IS NULL AND c.bid_id = " + EFF_BID_ID + "))"
            + "                AND CAST(c.id AS VARCHAR) = " + ID_TERM + ")))"
            + " OR p.stripe_payment_intent_id = CAST(:refTerm AS VARCHAR)"
            + " OR p.stripe_charge_id = CAST(:refTerm AS VARCHAR)"
            + " OR EXISTS (SELECT 1 FROM pawapay_operations o WHERE o.payment_id = p.id"
            + "            AND o.provider_transaction_id = CAST(:refTerm AS VARCHAR)))";

    private static String sum(String predicate, String alias) {
        return "COALESCE(SUM(CASE WHEN " + predicate + " THEN 1 ELSE 0 END), 0) AS \"" + alias + "\"";
    }

    /**
     * Nombres par vue et par alerte en un aller-retour. Les colonnes d'alerte comptent
     * chacune avec le prédicat BRUT de l'alerte (non exclusif, elles peuvent se recouper) ;
     * {@code attention} = paiements ayant au moins une alerte. {@code abandoned} compte les checkouts
     * abandonnés ({@code :abandonedBefore}) même quand le filtre les masque.
     */
    public static final String COUNTS_SELECT = "SELECT "
            + sum(ATTENTION_VIEW, "attention") + ", "
            + sum(ESCROW_VIEW, "escrow") + ", "
            + sum(RELEASED_RECENT_VIEW, "releasedRecent") + ", "
            + sum(REFUNDED_VIEW, "refunded") + ", "
            + sum(FAILED_VIEW, "failed") + ", "
            + "COUNT(*) AS \"all\", "
            + sum("p.status = 'PENDING' AND p.created_at < CAST(:abandonedBefore AS TIMESTAMP)", "abandoned") + ", "
            + sum(HELD_PREDICATE, "held") + ", "
            + sum(CHARGEBACK_OPEN_PREDICATE, "chargebackOpen") + ", "
            + sum(MM_FAILED_PREDICATE, "mmFailed") + ", "
            + sum(ESCROW_J48_PREDICATE, "escrowJ48") + " ";
}
