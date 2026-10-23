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
 * {@code :t4}, {@code :idTerm}, {@code :scope}, {@code :phones}, {@code :paymentMethod},
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
    private static final String NEGOTIATION_STATUSES = "('NEGOTIATING', 'NEGOTIATION_CLOSED')";
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

    /** Vue rapide {@code :view} (nom de {@link AdminBidView}). */
    public static final String VIEW_PREDICATE = "(:view = 'ALL'"
            + " OR (:view = 'TODO' AND " + ALERT_RANK + " > 0)"
            + " OR (:view = 'ACTIVE' AND b.status IN " + ACTIVE_STATUSES + ")"
            + " OR (:view = 'RECENT' AND b.status = 'COMPLETED'"
            + "     AND b.delivered_at >= " + NOW + " - INTERVAL '" + RECENT_DELIVERY_HOURS + "' HOUR)"
            + " OR (:view = 'COMPLETED' AND b.status = 'COMPLETED')"
            + " OR (:view = 'CLOSED' AND b.status IN " + CLOSED_STATUSES + "))";

    /** Repli d'une colonne : minuscules puis accents, même table que {@link AdminSearchTerms#fold}. */
    private static final String FOLD_OPEN = "TRANSLATE(LOWER(";
    private static final String FOLD_CLOSE =
            "), '" + AdminSearchTerms.ACCENT_FROM + "', '" + AdminSearchTerms.ACCENT_TO + "')";

    /**
     * Téléphone du destinataire réduit à ses chiffres (séparateurs usuels retirés), précédé d'un
     * {@code +} : « +221771234567 » comme « 771234567 » s'y trouvent.
     */
    private static final String RECIPIENT_PHONE_DIGITS = "'+' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE("
            + "b.recipient_phone, '+', ''), ' ', ''), '-', ''), '.', ''), '(', ''), ')', '')";

    /**
     * Texte replié des champs que {@code :scope} autorise, séparés par une espace : un jeton (sans
     * espace) ne peut donc pas chevaucher deux champs. Le téléphone du destinataire n'y entre que
     * si {@code :phones} est vrai, sous sa forme saisie et réduite à « + » suivi des chiffres.
     */
    private static final String HAYSTACK = FOLD_OPEN + "CONCAT_WS(' ',"
            + " CASE WHEN :scope IN ('ALL', 'TRACKING') THEN b.tracking_number END,"
            + " CASE WHEN :scope IN ('ALL', 'SENDER') THEN CONCAT_WS(' ', s.first_name, s.last_name, s.username) END,"
            + " CASE WHEN :scope IN ('ALL', 'TRAVELER') THEN CONCAT_WS(' ', t.first_name, t.last_name, t.username) END,"
            + " CASE WHEN :scope IN ('ALL', 'RECIPIENT') THEN b.recipient_name END,"
            + " CASE WHEN :scope IN ('ALL', 'RECIPIENT') AND :phones = TRUE"
            + "      THEN CONCAT_WS(' ', b.recipient_phone, " + RECIPIENT_PHONE_DIGITS + ") END,"
            + " CASE WHEN :scope IN ('ALL', 'TRIP') THEN CONCAT_WS(' ', a.departure_city, a.arrival_city) END)"
            + FOLD_CLOSE;

    private static final String ID_TERM = "LOWER(CAST(:idTerm AS VARCHAR))";

    /**
     * Recherche : chaque jeton non nul ({@code :t1} à {@code :t4}, déjà repliés et sans {@code %} ni
     * {@code _}) doit se trouver dans un champ du périmètre ; ou bien {@code :idTerm} est
     * l'identifiant exact du colis, de son trajet, de son paiement ou d'un de ses litiges.
     */
    public static final String SEARCH_PREDICATE = "(("
            + "(CAST(:t1 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t1 AS VARCHAR) || '%')"
            + " AND (CAST(:t2 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t2 AS VARCHAR) || '%')"
            + " AND (CAST(:t3 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t3 AS VARCHAR) || '%')"
            + " AND (CAST(:t4 AS VARCHAR) IS NULL OR " + HAYSTACK + " LIKE '%' || CAST(:t4 AS VARCHAR) || '%'))"
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
}
