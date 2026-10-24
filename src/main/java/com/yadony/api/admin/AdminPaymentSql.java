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

    /** Colis {@code lb} lié au fil de négociation du paiement {@code p} quand celui-ci n'a pas de colis direct. */
    public static final String LINKED_BID_CONDITION = "p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL"
            + " AND lb.linked_negotiation_thread_id = p.negotiation_thread_id";

    /**
     * Contexte d'un paiement {@code p} : colis direct {@code b} et son trajet {@code a}, fil {@code t} et sa
     * demande {@code r}, colis lié au fil {@code lb} et son trajet {@code la}. Seule définition, partagée par
     * {@link #FROM_JOIN} et par la liste mobile money ({@link AdminFinanceSql}).
     */
    public static final String PAYMENT_CONTEXT_JOIN = ""
            + " LEFT JOIN bids b ON b.id = p.bid_id"
            + " LEFT JOIN announcements a ON a.id = b.announcement_id"
            + " LEFT JOIN negotiation_threads t ON t.id = p.negotiation_thread_id"
            + " LEFT JOIN package_requests r ON r.id = t.package_request_id"
            + " LEFT JOIN bids lb ON " + LINKED_BID_CONDITION
            + " LEFT JOIN announcements la ON la.id = lb.announcement_id";

    public static final String FROM_JOIN = " payments p" + PAYMENT_CONTEXT_JOIN
            + " LEFT JOIN users s ON s.id = " + EFF_SENDER_ID
            + " LEFT JOIN users tr ON tr.id = " + EFF_TRAVELER_ID + " ";

    /** Statuts morts d'une opération pawaPay ({@code PawapayOperationStatus.DEAD}). */
    public static final String OP_FAILED = "('FAILED', 'SUBMIT_REJECTED')";

    public static final String HELD_PREDICATE = "(p.status = 'ESCROW' AND p.payout_held_at IS NOT NULL)";

    public static final String CHARGEBACK_OPEN_PREDICATE = "EXISTS (SELECT 1 FROM chargebacks c"
            + " WHERE c.status = 'OPEN' AND c.deleted_at IS NULL"
            + " AND (c.payment_id = p.id OR (c.payment_id IS NULL AND c.bid_id = " + EFF_BID_ID + ")))";

    /**
     * L'opération {@code o} est un versement ou un remboursement mobile money d'un paiement, en échec, sans
     * opération plus récente non échouée du même type pour le même paiement. Seule définition : l'alerte
     * {@link #MM_FAILED_PREDICATE} du paiement et le nombre {@code attention} de la liste mobile money la partagent.
     */
    public static final String MM_FAILED_OPERATION = "(o.payment_id IS NOT NULL"
            + " AND o.kind IN ('PAYOUT', 'REFUND') AND o.status IN " + OP_FAILED
            + " AND NOT EXISTS (SELECT 1 FROM pawapay_operations o2"
            + "     WHERE o2.payment_id = o.payment_id AND o2.kind = o.kind AND o2.created_at > o.created_at"
            + "     AND o2.status NOT IN " + OP_FAILED + "))";

    /** Versement ou remboursement mobile money en échec, sans opération plus récente non échouée du même type. */
    public static final String MM_FAILED_PREDICATE = "EXISTS (SELECT 1 FROM pawapay_operations o"
            + " WHERE o.payment_id = p.id AND " + MM_FAILED_OPERATION + ")";

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

    /** {@code :idTerm} est un identifiant exact du paiement (voir {@link #SEARCH_PREDICATE}). */
    private static final String ID_PREDICATE = "(CAST(:idTerm AS VARCHAR) IS NOT NULL AND ("
            + "     CAST(p.id AS VARCHAR) = " + ID_TERM
            + "     OR CAST(" + EFF_BID_ID + " AS VARCHAR) = " + ID_TERM
            + "     OR CAST(p.negotiation_thread_id AS VARCHAR) = " + ID_TERM
            + "     OR CAST(" + EFF_ANNOUNCEMENT_ID + " AS VARCHAR) = " + ID_TERM
            + "     OR EXISTS (SELECT 1 FROM pawapay_operations o WHERE o.payment_id = p.id"
            + "                AND CAST(o.id AS VARCHAR) = " + ID_TERM + ")"
            + "     OR EXISTS (SELECT 1 FROM chargebacks c WHERE c.deleted_at IS NULL"
            + "                AND (c.payment_id = p.id OR (c.payment_id IS NULL AND c.bid_id = " + EFF_BID_ID + "))"
            + "                AND CAST(c.id AS VARCHAR) = " + ID_TERM + ")))";

    /** {@code :refTerm} est une référence exacte du paiement (voir {@link #SEARCH_PREDICATE}). */
    private static final String REF_PREDICATE = "(p.stripe_payment_intent_id = CAST(:refTerm AS VARCHAR)"
            + " OR p.stripe_charge_id = CAST(:refTerm AS VARCHAR)"
            + " OR EXISTS (SELECT 1 FROM pawapay_operations o WHERE o.payment_id = p.id"
            + "            AND o.provider_transaction_id = CAST(:refTerm AS VARCHAR)))";

    /**
     * Identifiant ou référence exacts seulement (sans jeton ni montant) : la recherche de
     * {@code exactPaymentId} des suggestions réutilise ainsi la définition de la liste.
     */
    public static final String EXACT_PREDICATE = "(" + ID_PREDICATE + " OR " + REF_PREDICATE + ")";

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
            + " OR " + EXACT_PREDICATE + ")";

    /** Checkout abandonné (seule définition) : {@code :abandonedBefore} = {@code now - ABANDONED_AFTER_HOURS}. */
    public static final String ABANDONED_PREDICATE = "(p.status = 'PENDING'"
            + " AND p.created_at < CAST(:abandonedBefore AS TIMESTAMP))";

    /** Un paiement compte dans les nombres sauf s'il est abandonné et que {@code :hideAbandoned} les masque. */
    private static final String COUNTED = "NOT (CAST(:hideAbandoned AS BOOLEAN) AND " + ABANDONED_PREDICATE + ")";

    private static String sum(String predicate, String alias) {
        return "COALESCE(SUM(CASE WHEN " + COUNTED + " AND " + predicate + " THEN 1 ELSE 0 END), 0) AS \"" + alias + "\"";
    }

    /**
     * Nombres par vue et par alerte en un aller-retour, sur le {@code WHERE} de la liste SANS {@code view},
     * {@code alert}, {@code status} ni {@code hideAbandoned}. Les colonnes d'alerte comptent chacune avec le
     * prédicat BRUT de l'alerte (non exclusif, elles peuvent se recouper) ; {@code attention} = paiements ayant
     * au moins une alerte. {@code :hideAbandoned} (booléen) écarte les checkouts abandonnés de TOUTES les colonnes
     * sauf {@code abandoned}, qui les compte toujours ({@code :abandonedBefore}).
     */
    public static final String COUNTS_SELECT = "SELECT "
            + sum(ATTENTION_VIEW, "attention") + ", "
            + sum(ESCROW_VIEW, "escrow") + ", "
            + sum(RELEASED_RECENT_VIEW, "releasedRecent") + ", "
            + sum(REFUNDED_VIEW, "refunded") + ", "
            + sum(FAILED_VIEW, "failed") + ", "
            + sum("1 = 1", "all") + ", "
            + "COALESCE(SUM(CASE WHEN " + ABANDONED_PREDICATE + " THEN 1 ELSE 0 END), 0) AS \"abandoned\", "
            + sum(HELD_PREDICATE, "held") + ", "
            + sum(CHARGEBACK_OPEN_PREDICATE, "chargebackOpen") + ", "
            + sum(MM_FAILED_PREDICATE, "mmFailed") + ", "
            + sum(ESCROW_J48_PREDICATE, "escrowJ48") + " ";

    // ------------------------------------------------------------ suggestions de recherche

    /** Nombre maximal de propositions par groupe de {@code GET /admin/payments/suggest}. */
    public static final int SUGGEST_LIMIT = 5;

    /** Chaque jeton non nul ({@code :t1} à {@code :t4}) se trouve dans la colonne repliée {@code x.hay}. */
    private static final String HAY_TOKENS = "((CAST(:t1 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t1 AS VARCHAR) || '%')"
            + " AND (CAST(:t2 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t2 AS VARCHAR) || '%')"
            + " AND (CAST(:t3 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t3 AS VARCHAR) || '%')"
            + " AND (CAST(:t4 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t4 AS VARCHAR) || '%'))";

    /**
     * Paiements comptés par les suggestions : non supprimés, checkouts abandonnés exclus
     * ({@code :abandonedBefore}). Les vues et filtres de la liste ne s'appliquent pas.
     */
    private static final String SUGGEST_PAYMENT_FILTER = "p.deleted_at IS NULL AND NOT " + ABANDONED_PREDICATE;

    private static String suggestPeople(String alias, String first, String last, String username) {
        return "SELECT x.uid AS \"idText\", COUNT(*) AS \"total\" FROM ("
                + "SELECT CAST(" + alias + ".id AS VARCHAR) AS uid, "
                + AdminBidSql.FOLD_OPEN + "CONCAT_WS(' ', " + first + ", " + last + ", " + username + ")"
                + AdminBidSql.FOLD_CLOSE + " AS hay"
                + " FROM" + FROM_JOIN + "WHERE " + SUGGEST_PAYMENT_FILTER
                + " AND " + alias + ".id IS NOT NULL AND " + alias + ".deleted_at IS NULL) x"
                + " WHERE " + HAY_TOKENS
                + " GROUP BY x.uid ORDER BY COUNT(*) DESC, MIN(x.hay), x.uid LIMIT " + SUGGEST_LIMIT;
    }

    /** Expéditeurs effectifs (comptes non supprimés) dont le nom correspond, avec leur nombre de paiements. */
    public static final String SUGGEST_SENDERS = suggestPeople("s",
            "s.first_name", "s.last_name", "s.username");

    /** Voyageurs effectifs (comptes non supprimés) dont le nom correspond, avec leur nombre de paiements. */
    public static final String SUGGEST_TRAVELERS = suggestPeople("tr",
            "tr.first_name", "tr.last_name", "tr.username");

    /**
     * Trajets groupés par couple (départ, arrivée) REPLIÉ ; les villes montrées sont les plus petites des
     * valeurs d'origine. Chaque jeton doit se trouver dans l'une des deux villes.
     */
    public static final String SUGGEST_TRIPS = "SELECT MIN(x.dep) AS \"departureCity\", MIN(x.arr) AS \"arrivalCity\","
            + " COUNT(*) AS \"total\" FROM ("
            + "SELECT " + EFF_DEPARTURE + " AS dep, " + EFF_ARRIVAL + " AS arr, "
            + AdminBidSql.FOLD_OPEN + EFF_DEPARTURE + AdminBidSql.FOLD_CLOSE + " AS fdep, "
            + AdminBidSql.FOLD_OPEN + EFF_ARRIVAL + AdminBidSql.FOLD_CLOSE + " AS farr, "
            + AdminBidSql.FOLD_OPEN + "CONCAT_WS(' ', " + EFF_DEPARTURE + ", " + EFF_ARRIVAL + ")"
            + AdminBidSql.FOLD_CLOSE + " AS hay"
            + " FROM" + FROM_JOIN + "WHERE " + SUGGEST_PAYMENT_FILTER
            + " AND " + EFF_DEPARTURE + " IS NOT NULL AND " + EFF_ARRIVAL + " IS NOT NULL) x"
            + " WHERE " + HAY_TOKENS
            + " GROUP BY x.fdep, x.farr ORDER BY COUNT(*) DESC, x.fdep, x.farr LIMIT " + SUGGEST_LIMIT;

    /** Paiements dont le numéro de suivi effectif correspond, les plus récents d'abord. */
    public static final String SUGGEST_PARCELS = "SELECT x.pid AS \"paymentIdText\", x.tracking AS \"trackingNumber\","
            + " x.status AS \"status\", x.amount AS \"amount\", x.currency AS \"currency\" FROM ("
            + "SELECT CAST(p.id AS VARCHAR) AS pid, " + EFF_TRACKING + " AS tracking, p.status AS status,"
            + " p.amount AS amount, p.currency AS currency, p.created_at AS created, "
            + AdminBidSql.FOLD_OPEN + EFF_TRACKING + AdminBidSql.FOLD_CLOSE + " AS hay"
            + " FROM" + FROM_JOIN + "WHERE " + SUGGEST_PAYMENT_FILTER + " AND " + EFF_TRACKING + " IS NOT NULL) x"
            + " WHERE " + HAY_TOKENS
            + " ORDER BY x.created DESC, x.pid DESC LIMIT " + SUGGEST_LIMIT;

    /**
     * Paiements identifiés exactement par {@code :idTerm} / {@code :refTerm} ({@link #EXACT_PREDICATE}) : deux
     * lignes au plus, car un identifiant qui en désigne plusieurs est ambigu.
     */
    public static final String SUGGEST_EXACT = "SELECT CAST(p.id AS VARCHAR) FROM" + FROM_JOIN
            + "WHERE p.deleted_at IS NULL AND " + EXACT_PREDICATE + " ORDER BY p.id LIMIT 2";
}
