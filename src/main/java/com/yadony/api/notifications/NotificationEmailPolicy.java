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
        TIP("emailAlertsTips");

        private final String pref;

        Kind(String pref) {
            this.pref = pref;
        }

        /** Réglage qui coupe cette famille, ou vide si elle part toujours. */
        public Optional<String> pref() {
            return Optional.ofNullable(pref);
        }
    }

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
            Map.entry("PARCEL_UNCLAIMED", Kind.RECAP)
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

    /** Types dont l'e-mail a une ligne propre en plus de celle de sa famille. */
    static final Set<String> TYPE_DETAILS = Set.of("DELIVERY_CONFIRMED");

    private NotificationEmailPolicy() {}

    /** Le type a-t-il une ligne propre ({@code email.notif.type.<type>.detail}) ? */
    public static boolean hasTypeDetail(String type) {
        return type != null && TYPE_DETAILS.contains(type);
    }

    public static Optional<Kind> kindOf(String type, Map<String, String> data) {
        if (type == null) {
            return Optional.empty();
        }
        if (isCorridorDigest(type, data)) {
            return Optional.of(Kind.ALERT);
        }
        Kind kind = WITH_PUSH.get(type);
        return Optional.ofNullable(kind != null ? kind : INSTEAD_OF_PUSH.get(type));
    }

    /** L'e-mail remplace-t-il le push, pour peu qu'il puisse partir ? */
    public static boolean replacesPush(String type, Map<String, String> data) {
        return type != null && (INSTEAD_OF_PUSH.containsKey(type) || isCorridorDigest(type, data));
    }

    private static boolean isCorridorDigest(String type, Map<String, String> data) {
        return CORRIDOR_ALERT.equals(type) && data != null && "true".equals(data.get(DIGEST_FLAG));
    }
}
