package com.yadony.api.notifications;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Notifications qui partent aussi par e-mail (plan de réduction du bruit, étape 5).
 *
 * <p>Deux familles :
 * <ul>
 *   <li><b>push + e-mail</b> : le push reste court, l'e-mail garde une trace écrite (reçu,
 *   décision, récapitulatif d'un colis) ;</li>
 *   <li><b>e-mail à la place du push</b> ({@link #replacesPush}) : rien d'urgent, le
 *   téléphone n'a pas à sonner. Le push part quand même si l'e-mail ne peut pas partir
 *   (compte sans adresse, e-mails coupés) : rien ne doit se perdre.</li>
 * </ul>
 *
 * <p>Les reçus, les décisions et le compte partent toujours ; les récapitulatifs d'une part,
 * les alertes et conseils d'autre part, se coupent dans les réglages ({@link Kind#pref()}).
 */
public final class NotificationEmailPolicy {

    public enum Kind {
        /** Argent reçu, versé ou crédité : l'e-mail vaut reçu. */
        RECEIPT(null),
        /** Décision de l'équipe (litige, absence, modération, identité) : confirmée par écrit. */
        DECISION(null),
        /** Démarche à finir sur le compte (versements) : expliquée par écrit. */
        ACCOUNT(null),
        /** Étape importante d'un colis : réservation, livraison, annulation, retour. */
        RECAP("emailRecaps"),
        /** Alerte posée par l'utilisateur : récapitulatif de trajets, prix d'un vol. */
        ALERT("emailAlertsTips"),
        /** Conseil pour bien démarrer. */
        TIP("emailAlertsTips"),
        /**
         * Trajet publié sur un corridor cherché sans résultat, sans alerte posée (V362). Même
         * réglage que les alertes ; l'e-mail porte en plus un lien de désinscription en un clic.
         */
        SUGGESTION("emailAlertsTips"),
        /**
         * Relance automatique de la vérification d'identité, laissée en plan. Même réglage que
         * les alertes et conseils ; l'e-mail porte un lien de désinscription en un clic des
         * relances. Le push part aussi : l'e-mail s'y ajoute, jamais à sa place.
         */
        REMINDER("emailAlertsTips"),
        /**
         * DAC7 : demande ou rappel du numéro fiscal. Même réglage que les alertes et conseils (préférences
         * respectées) ; le push part aussi.
         */
        TAX_REQUEST("emailAlertsTips"),
        /** DAC7 : récapitulatif annuel des montants transmis, information légale qui part toujours. */
        TAX_STATEMENT(null);

        private final String pref;

        Kind(String pref) {
            this.pref = pref;
        }

        /** Réglage qui coupe cette famille, ou vide si elle part toujours. */
        public Optional<String> pref() {
            return Optional.ofNullable(pref);
        }
    }

    /** Suggestion selon les recherches : push et e-mail, ce dernier avec désinscription. */
    public static final String SEARCH_SUGGESTION = "SEARCH_SUGGESTION";

    static final Map<String, Kind> WITH_PUSH = Map.ofEntries(
            Map.entry("PAYMENT_RELEASED", Kind.RECEIPT),
            Map.entry("MOBILE_MONEY_PAYMENT_CONFIRMED", Kind.RECEIPT),
            Map.entry("WALLET_ADJUSTED", Kind.RECEIPT),
            Map.entry("ACCOUNT_SUSPENDED", Kind.DECISION),
            Map.entry("ACCOUNT_DELETION_CANCELLED", Kind.DECISION),
            Map.entry("KYC_ACTION_REQUIRED", Kind.DECISION),
            Map.entry("KYC_RESET", Kind.DECISION),
            Map.entry("ANNOUNCEMENT_REMOVED", Kind.DECISION),
            Map.entry("PACKAGE_REQUEST_REMOVED", Kind.DECISION),
            Map.entry("ADMIN_WARNING", Kind.DECISION),
            Map.entry("BID_CANCELLED_BY_ADMIN", Kind.DECISION),
            Map.entry("NOSHOW_DECISION", Kind.DECISION),
            Map.entry("DISPUTE_OPENED", Kind.DECISION),
            Map.entry("DISPUTE_RESOLVED", Kind.DECISION),
            Map.entry("BID_ACCEPTED", Kind.RECAP),
            Map.entry("request_accepted", Kind.RECAP),
            Map.entry("DELIVERY_CONFIRMED", Kind.RECAP),
            Map.entry("TRIP_CANCELLED", Kind.RECAP),
            Map.entry("PARCEL_RETURN_REQUIRED", Kind.RECAP),
            Map.entry("PARCEL_RETURN_TO_SENDER", Kind.RECAP),
            Map.entry("RETURN_DEADLINE_EXPIRED", Kind.RECAP),
            Map.entry("PARCEL_UNCLAIMED", Kind.RECAP),
            Map.entry(SEARCH_SUGGESTION, Kind.SUGGESTION),
            Map.entry(NotificationTypes.KYC_VERIFICATION_REMINDER, Kind.REMINDER),
            Map.entry("TAX_INFO_REQUEST", Kind.TAX_REQUEST),
            Map.entry("TAX_STATEMENT", Kind.TAX_STATEMENT)
    );

    static final Map<String, Kind> INSTEAD_OF_PUSH = Map.of(
            "wallet_topup_confirmed", Kind.RECEIPT,
            "STRIPE_ONBOARDING_INCOMPLETE", Kind.ACCOUNT,
            "FLIGHT_PRICE_ALERT", Kind.ALERT,
            "FIRST_ACTION_REMINDER", Kind.TIP
    );

    /** Le récapitulatif de 9 h des alertes de trajet ; l'alerte en direct, elle, sonne. */
    static final String CORRIDOR_ALERT = "CORRIDOR_ALERT";
    static final String DIGEST_FLAG = "digest";

    /**
     * Types (effectifs, voir {@link NotificationTypes#effectiveType}) dont l'e-mail a une ligne
     * propre en plus de celle de sa famille.
     */
    static final Set<String> TYPE_DETAILS = Set.of("DELIVERY_CONFIRMED", NotificationTypes.KYC_VERIFICATION_REMINDER);

    private NotificationEmailPolicy() {}

    /**
     * Le type a-t-il une ligne propre ({@code email.notif.type.<type>.detail}) ? Attend le type
     * effectif : c'est celui que {@link NotificationEmailService} transmet au gabarit.
     */
    public static boolean hasTypeDetail(String type) {
        return type != null && TYPE_DETAILS.contains(type);
    }

    /**
     * Types (effectifs) dont le bouton a un libellé propre ({@code email.notif.cta.<type>}) à la
     * place de « Ouvrir Yadony », sans changer son lien ni la ligne de la famille. Distinct de
     * {@link NotificationEmailLink} : celui-là remplace aussi le lien et la ligne, et vaut pour
     * le type d'origine (il toucherait le vrai refus de vérification).
     */
    static final Set<String> TYPE_CTAS = Set.of(NotificationTypes.KYC_VERIFICATION_REMINDER);

    /** Le bouton du type a-t-il un libellé propre ? Attend le type effectif. */
    public static boolean hasTypeCta(String type) {
        return type != null && TYPE_CTAS.contains(type);
    }

    /**
     * Famille de l'e-mail, d'après le type effectif : une relance de vérification
     * ({@code KYC_ACTION_REQUIRED} + {@code reminder=true}) est une {@link Kind#REMINDER}, le
     * même type sans drapeau reste une {@link Kind#DECISION}.
     */
    public static Optional<Kind> kindOf(String type, Map<String, String> data) {
        String effective = NotificationTypes.effectiveType(type, data);
        if (effective == null) {
            return Optional.empty();
        }
        if (isCorridorDigest(effective, data)) {
            return Optional.of(Kind.ALERT);
        }
        Kind kind = WITH_PUSH.get(effective);
        return Optional.ofNullable(kind != null ? kind : INSTEAD_OF_PUSH.get(effective));
    }

    /** L'e-mail remplace-t-il le push, pour peu qu'il puisse partir ? */
    public static boolean replacesPush(String type, Map<String, String> data) {
        String effective = NotificationTypes.effectiveType(type, data);
        return effective != null && (INSTEAD_OF_PUSH.containsKey(effective) || isCorridorDigest(effective, data));
    }

    private static boolean isCorridorDigest(String type, Map<String, String> data) {
        return CORRIDOR_ALERT.equals(type) && data != null && "true".equals(data.get(DIGEST_FLAG));
    }
}
