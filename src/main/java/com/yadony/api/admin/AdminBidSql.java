package com.yadony.api.admin;

/**
 * Fragments SQL natifs de la liste des colis de l'admin, concaténés dans les {@code @Query} de
 * {@code BidRepository} (constantes de compilation). Ils tournent tels quels sur PostgreSQL et sur
 * H2 en mode PostgreSQL (tests) : {@code TRANSLATE}, {@code CONCAT_WS}, {@code INTERVAL 'n' HOUR}
 * et {@code CAST} sont communs aux deux.
 *
 * <p>Alias attendus : {@code b} (bids), {@code a} (announcements), {@code s} (expéditeur),
 * {@code t} (voyageur), posés par {@link #FROM_JOIN}.
 *
 * <p>Paramètres nommés : {@code :view}, {@code :status}, {@code :announcementId}, {@code :t1} à
 * {@code :t4}, {@code :idTerm}, {@code :scope}, {@code :phoneSuffix}, {@code :paymentMethod},
 * {@code :content}, {@code :alertRank}, {@code :from}, {@code :to}, {@code :now},
 * {@code :lateBefore}. Les paramètres facultatifs passent par {@code CAST(:p AS …)} : PostgreSQL
 * ne sait pas typer un paramètre lié à null sans lui.
 */
public final class AdminBidSql {

    private AdminBidSql() {
    }

    /** Un colis en attente de paiement depuis plus longtemps est une alerte. */
    public static final int UNPAID_AFTER_HOURS = 24;
    /** Jours de grâce après l'arrivée prévue avant qu'un colis en route soit « en retard ». */
    public static final int LATE_AFTER_DAYS = 1;
    /** Fenêtre de la vue « livrés récemment ». */
    public static final int RECENT_DELIVERY_HOURS = 72;

    public static final String FROM_JOIN = " bids b"
            + " LEFT JOIN announcements a ON a.id = b.announcement_id AND a.deleted_at IS NULL"
            + " LEFT JOIN users s ON s.id = b.sender_id"
            + " LEFT JOIN users t ON t.id = a.traveler_id ";

    private static final String NOW = "CAST(:now AS TIMESTAMP)";

    /** Fils de négociation : jamais des colis (voir {@code BidStatus.NEGOTIATION_STATUSES}). */
    public static final String NEGOTIATION_STATUSES = "('NEGOTIATING', 'NEGOTIATION_CLOSED')";
    private static final String ACTIVE_STATUSES = "('AWAITING_PAYMENT', 'PENDING', 'PAYMENT_ESCROWED',"
            + " 'ACCEPTED', 'HANDED_OVER', 'IN_TRANSIT', 'ARRIVED')";
    private static final String CLOSED_STATUSES = "('REJECTED', 'CANCELLED', 'EXPIRED', 'PARCEL_REFUSED', 'NO_SHOW')";
    private static final String LATE_STATUSES = "('ACCEPTED', 'HANDED_OVER', 'IN_TRANSIT')";

    /**
     * Rang d'alerte du colis, le plus urgent l'emporte : 1 litige ouvert (quel que soit le statut),
     * 2 no-show, 3 paiement attendu depuis plus de {@value #UNPAID_AFTER_HOURS} h, 4 en route après
     * l'arrivée prévue (départ à défaut) plus {@value #LATE_AFTER_DAYS} jour de grâce ; 0 sinon.
     * Seule définition de l'alerte : liste, tri, nombres et étiquette la réutilisent.
     */
    public static final String ALERT_RANK = "(CASE"
            + " WHEN EXISTS (SELECT 1 FROM disputes d"
            + "              WHERE d.bid_id = b.id AND d.status = 'OPEN' AND d.deleted_at IS NULL) THEN 1"
            + " WHEN b.status = 'NO_SHOW' THEN 2"
            + " WHEN b.status = 'AWAITING_PAYMENT'"
            + "      AND b.updated_at < " + NOW + " - INTERVAL '" + UNPAID_AFTER_HOURS + "' HOUR THEN 3"
            + " WHEN b.status IN " + LATE_STATUSES
            + "      AND b.delivered_at IS NULL"
            + "      AND COALESCE(a.arrival_date, a.departure_date) < CAST(:lateBefore AS DATE) THEN 4"
            + " ELSE 0 END)";

    /** Prédicat de chaque vue (hors {@code ALL}) : seule définition, la liste et les nombres la partagent. */
    public static final String TODO_VIEW = "(" + ALERT_RANK + " > 0)";
    public static final String ACTIVE_VIEW = "(b.status IN " + ACTIVE_STATUSES + ")";
    public static final String RECENT_VIEW = "(b.status = 'COMPLETED'"
            + " AND b.delivered_at >= " + NOW + " - INTERVAL '" + RECENT_DELIVERY_HOURS + "' HOUR)";
    public static final String COMPLETED_VIEW = "(b.status = 'COMPLETED')";
    public static final String CLOSED_VIEW = "(b.status IN " + CLOSED_STATUSES + ")";

    /** Vue rapide {@code :view} (nom de {@link AdminBidView}). */
    public static final String VIEW_PREDICATE = "(:view = 'ALL'"
            + " OR (:view = 'TODO' AND " + TODO_VIEW + ")"
            + " OR (:view = 'ACTIVE' AND " + ACTIVE_VIEW + ")"
            + " OR (:view = 'RECENT' AND " + RECENT_VIEW + ")"
            + " OR (:view = 'COMPLETED' AND " + COMPLETED_VIEW + ")"
            + " OR (:view = 'CLOSED' AND " + CLOSED_VIEW + "))";

    /**
     * Nombres par vue et par alerte en un aller-retour (agrégation conditionnelle). Les colonnes
     * suivent {@code BidRepository.AdminCountsRow}. {@code COALESCE} : sans ligne, {@code SUM} vaut NULL.
     * Une alerte par colis (la plus urgente) : les quatre alertes somment à {@code todo}.
     */
    public static final String COUNTS_SELECT = "SELECT "
            + "COALESCE(SUM(CASE WHEN " + TODO_VIEW + " THEN 1 ELSE 0 END), 0)"
            + " AS \"todo\", "
            + "COALESCE(SUM(CASE WHEN " + ACTIVE_VIEW + " THEN 1 ELSE 0 END), 0)"
            + " AS \"active\", "
            + "COALESCE(SUM(CASE WHEN " + RECENT_VIEW + " THEN 1 ELSE 0 END), 0)"
            + " AS \"recent\", "
            + "COALESCE(SUM(CASE WHEN " + COMPLETED_VIEW + " THEN 1 ELSE 0 END), 0)"
            + " AS \"completed\", "
            + "COALESCE(SUM(CASE WHEN " + CLOSED_VIEW + " THEN 1 ELSE 0 END), 0)"
            + " AS \"closed\", "
            + "COUNT(*) AS \"all\", "
            + "COALESCE(SUM(CASE WHEN " + ALERT_RANK + " = 1" + " THEN 1 ELSE 0 END), 0)"
            + " AS \"disputeOpen\", "
            + "COALESCE(SUM(CASE WHEN " + ALERT_RANK + " = 2" + " THEN 1 ELSE 0 END), 0)"
            + " AS \"noShow\", "
            + "COALESCE(SUM(CASE WHEN " + ALERT_RANK + " = 3" + " THEN 1 ELSE 0 END), 0)"
            + " AS \"paymentPending\", "
            + "COALESCE(SUM(CASE WHEN " + ALERT_RANK + " = 4" + " THEN 1 ELSE 0 END), 0)"
            + " AS \"late\" ";

    /**
     * Repli d'une colonne : minuscules, accents puis apostrophes (REPLACE imbriqués, compatible H2 et
     * PostgreSQL), même règle que {@link AdminSearchTerms#fold}.
     */
    private static final String FOLD_OPEN = "REPLACE(REPLACE(TRANSLATE(LOWER(";
    private static final String FOLD_CLOSE =
            "), '" + AdminSearchTerms.ACCENT_FROM + "', '" + AdminSearchTerms.ACCENT_TO + "'), '''', ''), '\u2019', '')";

    /** Téléphone du destinataire réduit à ses chiffres (séparateurs usuels retirés). */
    private static final String RECIPIENT_PHONE_DIGITS = "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE("
            + "b.recipient_phone, '+', ''), ' ', ''), '-', ''), '.', ''), '(', ''), ')', '')";

    /**
     * Texte replié des champs que {@code :scope} autorise, séparés par une espace : un jeton (sans
     * espace) ne peut donc pas chevaucher deux champs. Le téléphone du destinataire n'y entre
     * jamais : sa recherche par sous-chaîne permettrait de reconstituer un numéro affiché masqué
     * (voir {@link #RECIPIENT_PHONE_PREDICATE}).
     */
    private static final String HAYSTACK = FOLD_OPEN + "CONCAT_WS(' ',"
            + " CASE WHEN :scope IN ('ALL', 'TRACKING') THEN b.tracking_number END,"
            + " CASE WHEN :scope IN ('ALL', 'SENDER') THEN CONCAT_WS(' ', s.first_name, s.last_name, s.username) END,"
            + " CASE WHEN :scope IN ('ALL', 'TRAVELER') THEN CONCAT_WS(' ', t.first_name, t.last_name, t.username) END,"
            + " CASE WHEN :scope IN ('ALL', 'RECIPIENT') THEN b.recipient_name END,"
            + " CASE WHEN :scope IN ('ALL', 'TRIP') THEN CONCAT_WS(' ', a.departure_city, a.arrival_city) END)"
            + FOLD_CLOSE;

    /**
     * Téléphone du destinataire : seulement si {@code :phoneSuffix} est posé (droit {@code USER_VIEW}
     * et saisie entièrement numérique d'au moins huit chiffres, voir
     * {@link AdminSearchTerms#phoneSuffix}) et que le périmètre couvre le destinataire. Les chiffres
     * du numéro stocké doivent se <em>terminer</em> par ceux saisis ({@code :phoneSuffix} ne porte que
     * des chiffres : aucun joker à échapper) ET la saisie doit couvrir tout le numéro, à l'indicatif
     * pays près : au plus {@value #PHONE_MAX_UNTYPED_DIGITS} chiffres de tête non saisis. Sans cet
     * écart borné, les quatre derniers chiffres affichés laisseraient sonder les chiffres masqués.
     * Pas de préfixe ni de milieu.
     */
    /** Chiffres de tête que la saisie peut omettre : l'indicatif pays (un à trois chiffres). */
    public static final int PHONE_MAX_UNTYPED_DIGITS = 3;

    private static final String RECIPIENT_PHONE_PREDICATE = "(CAST(:phoneSuffix AS VARCHAR) IS NOT NULL"
            + " AND :scope IN ('ALL', 'RECIPIENT')"
            + " AND " + RECIPIENT_PHONE_DIGITS + " LIKE '%' || CAST(:phoneSuffix AS VARCHAR)"
            + " AND LENGTH(" + RECIPIENT_PHONE_DIGITS + ") - LENGTH(CAST(:phoneSuffix AS VARCHAR))"
            + "     <= " + PHONE_MAX_UNTYPED_DIGITS + ")";

    private static final String ID_TERM = "LOWER(CAST(:idTerm AS VARCHAR))";

    /**
     * Recherche : chaque jeton non nul ({@code :t1} à {@code :t4}, déjà repliés et sans {@code %} ni
     * {@code _}) doit se trouver dans un champ du périmètre ; ou bien {@code :idTerm} est
     * l'identifiant exact du colis, de son trajet, de son paiement ou d'un de ses litiges ; ou bien
     * la saisie est un numéro qui termine le téléphone du destinataire ({@link #RECIPIENT_PHONE_PREDICATE}).
     */
    public static final String SEARCH_PREDICATE = "(("
            + "(CAST(:t1 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t1 AS VARCHAR) || '%')"
            + " AND (CAST(:t2 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t2 AS VARCHAR) || '%')"
            + " AND (CAST(:t3 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t3 AS VARCHAR) || '%')"
            + " AND (CAST(:t4 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t4 AS VARCHAR) || '%'))"
            + " OR " + RECIPIENT_PHONE_PREDICATE
            + " OR (CAST(:idTerm AS VARCHAR) IS NOT NULL AND ("
            + "     CAST(b.id AS VARCHAR) = " + ID_TERM
            + "     OR CAST(b.announcement_id AS VARCHAR) = " + ID_TERM
            + "     OR EXISTS (SELECT 1 FROM payments p WHERE p.bid_id = b.id AND CAST(p.id AS VARCHAR) = " + ID_TERM + ")"
            + "     OR EXISTS (SELECT 1 FROM disputes d WHERE d.bid_id = b.id AND CAST(d.id AS VARCHAR) = " + ID_TERM + "))))";

    /**
     * Filtres : colis non supprimé, fils de négociation exclus sauf si {@code :status} les nomme,
     * trajet, moyen de paiement, contenu replié, rang d'alerte et bornes de création.
     */
    public static final String FILTER_PREDICATE = "b.deleted_at IS NULL"
            + " AND ((CAST(:status AS VARCHAR) IS NULL AND b.status NOT IN " + NEGOTIATION_STATUSES + ")"
            + "      OR b.status = CAST(:status AS VARCHAR))"
            + " AND (CAST(:announcementId AS VARCHAR) IS NULL OR b.announcement_id = CAST(:announcementId AS UUID))"
            + " AND (CAST(:paymentMethod AS VARCHAR) IS NULL OR b.payment_method = CAST(:paymentMethod AS VARCHAR))"
            + " AND (CAST(:content AS VARCHAR) IS NULL"
            + "      OR " + FOLD_OPEN + "b.content_category" + FOLD_CLOSE + " LIKE '%' || CAST(:content AS VARCHAR) || '%')"
            + " AND (CAST(:alertRank AS INTEGER) IS NULL OR " + ALERT_RANK + " = CAST(:alertRank AS INTEGER))"
            + " AND (CAST(:from AS TIMESTAMP) IS NULL OR b.created_at >= CAST(:from AS TIMESTAMP))"
            + " AND (CAST(:to AS TIMESTAMP) IS NULL OR b.created_at <= CAST(:to AS TIMESTAMP))";

    /** Vue « À traiter » : rang croissant puis plus ancien d'abord ; sinon plus récent d'abord. */
    public static final String ORDER_BY = "CASE WHEN :view = 'TODO' THEN " + ALERT_RANK + " END ASC,"
            + " CASE WHEN :view = 'TODO' THEN b.created_at END ASC,"
            + " b.created_at DESC, b.id DESC";

    // ------------------------------------------------------------ suggestions de recherche

    /** Nombre maximal de propositions par groupe de {@code GET /admin/bids/suggest}. */
    public static final int SUGGEST_LIMIT = 5;

    /**
     * Chaque jeton non nul ({@code :t1} à {@code :t4}) doit se trouver dans la colonne repliée
     * {@code x.hay} des sous-requêtes de suggestion (constante : une annotation n'accepte pas d'appel).
     */
    private static final String HAY_TOKENS = "((CAST(:t1 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t1 AS VARCHAR) || '%')"
            + " AND (CAST(:t2 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t2 AS VARCHAR) || '%')"
            + " AND (CAST(:t3 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t3 AS VARCHAR) || '%')"
            + " AND (CAST(:t4 AS VARCHAR) IS NULL OR x.hay LIKE '%' || CAST(:t4 AS VARCHAR) || '%'))";

    /** Colis comptés par les suggestions : non supprimés, fils de négociation exclus. */
    private static final String SUGGEST_BID_FILTER = "b.deleted_at IS NULL AND b.status NOT IN " + NEGOTIATION_STATUSES;

    /** Expéditeurs (comptes non supprimés) dont le nom correspond, avec leur nombre de colis. */
    public static final String SUGGEST_SENDERS = "SELECT x.uid AS \"idText\", COUNT(*) AS \"total\" FROM ("
            + "SELECT CAST(s.id AS VARCHAR) AS uid, "
            + FOLD_OPEN + "CONCAT_WS(' ', s.first_name, s.last_name, s.username)" + FOLD_CLOSE + " AS hay"
            + " FROM bids b JOIN users s ON s.id = b.sender_id AND s.deleted_at IS NULL"
            + " WHERE " + SUGGEST_BID_FILTER + ") x"
            + " WHERE " + HAY_TOKENS
            + " GROUP BY x.uid ORDER BY COUNT(*) DESC, MIN(x.hay), x.uid LIMIT " + SUGGEST_LIMIT;

    /** Voyageurs (comptes non supprimés) du trajet des colis, avec leur nombre de colis. */
    public static final String SUGGEST_TRAVELERS = "SELECT x.uid AS \"idText\", COUNT(*) AS \"total\" FROM ("
            + "SELECT CAST(t.id AS VARCHAR) AS uid, "
            + FOLD_OPEN + "CONCAT_WS(' ', t.first_name, t.last_name, t.username)" + FOLD_CLOSE + " AS hay"
            + " FROM bids b JOIN announcements a ON a.id = b.announcement_id AND a.deleted_at IS NULL"
            + " JOIN users t ON t.id = a.traveler_id AND t.deleted_at IS NULL"
            + " WHERE " + SUGGEST_BID_FILTER + ") x"
            + " WHERE " + HAY_TOKENS
            + " GROUP BY x.uid ORDER BY COUNT(*) DESC, MIN(x.hay), x.uid LIMIT " + SUGGEST_LIMIT;

    /** Destinataires groupés par nom replié ; le nom montré est le plus petit des noms d'origine. */
    public static final String SUGGEST_RECIPIENTS = "SELECT MIN(x.name) AS \"name\", COUNT(*) AS \"total\" FROM ("
            + "SELECT b.recipient_name AS name, " + FOLD_OPEN + "b.recipient_name" + FOLD_CLOSE + " AS hay"
            + " FROM bids b WHERE " + SUGGEST_BID_FILTER + " AND b.recipient_name IS NOT NULL) x"
            + " WHERE " + HAY_TOKENS
            + " GROUP BY x.hay ORDER BY COUNT(*) DESC, x.hay LIMIT " + SUGGEST_LIMIT;

    /**
     * Trajets groupés par couple (départ, arrivée) REPLIÉ (« Paris » et « paris » forment un seul trajet) ;
     * les villes montrées sont les plus petites des valeurs d'origine. Chaque jeton doit se trouver
     * dans l'une des deux villes.
     */
    public static final String SUGGEST_TRIPS = "SELECT MIN(x.dep) AS \"departureCity\", MIN(x.arr) AS \"arrivalCity\","
            + " COUNT(*) AS \"total\" FROM ("
            + "SELECT a.departure_city AS dep, a.arrival_city AS arr, "
            + FOLD_OPEN + "a.departure_city" + FOLD_CLOSE + " AS fdep, "
            + FOLD_OPEN + "a.arrival_city" + FOLD_CLOSE + " AS farr, "
            + FOLD_OPEN + "CONCAT_WS(' ', a.departure_city, a.arrival_city)" + FOLD_CLOSE + " AS hay"
            + " FROM bids b JOIN announcements a ON a.id = b.announcement_id AND a.deleted_at IS NULL"
            + " WHERE " + SUGGEST_BID_FILTER + ") x"
            + " WHERE " + HAY_TOKENS
            + " GROUP BY x.fdep, x.farr ORDER BY COUNT(*) DESC, x.fdep, x.farr LIMIT " + SUGGEST_LIMIT;

    /** Colis dont le numéro de suivi correspond, les plus récents d'abord. */
    public static final String SUGGEST_PARCELS = "SELECT x.bid_id AS \"bidIdText\", x.tracking AS \"trackingNumber\","
            + " x.status AS \"status\", x.content AS \"content\" FROM ("
            + "SELECT CAST(b.id AS VARCHAR) AS bid_id, b.tracking_number AS tracking, b.status AS status,"
            + " b.content_category AS content, b.created_at AS created, "
            + FOLD_OPEN + "b.tracking_number" + FOLD_CLOSE + " AS hay"
            + " FROM bids b WHERE " + SUGGEST_BID_FILTER + " AND b.tracking_number IS NOT NULL) x"
            + " WHERE " + HAY_TOKENS
            + " ORDER BY x.created DESC, x.bid_id DESC LIMIT " + SUGGEST_LIMIT;
}
