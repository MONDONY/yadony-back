package com.yadony.api.videomodal;

/**
 * Endroit ou moment de l'app où un modal peut s'ouvrir. Les huit premières valeurs sont le « où »
 * (écran ou bouton) ; les autres sont des « moments » déclenchés par l'app quand quelque chose se
 * passe (étape 2) et exigent {@code caps=moments}. Les étapes du colis ({@code PARCEL_*}, en dernier)
 * exigent aussi {@code caps=parcel}. Nouvelles valeurs toujours ajoutées à la fin.
 */
public enum VideoModalTrigger {
    /** Premier écran après lancement, une fois l'app prête. */
    APP_OPEN,
    SEARCH_TAB,
    ACTIVITIES_TAB,
    /** Onglet Suivi. */
    TRACKING_TAB,
    MESSAGES_TAB,
    /** Onglet Moi. */
    PROFILE_TAB,
    /** Bouton « Publier un trajet » : modal AVANT le formulaire. */
    PUBLISH_TRIP,
    /** Bouton « Envoyer un colis » : modal AVANT le formulaire. */
    SEND_PARCEL,

    // ── Moments d'action réussie ───────────────────────────────────────────
    /** Livraison confirmée, côté expéditeur ou voyageur. */
    DELIVERY_CONFIRMED,
    PAYMENT_SUCCEEDED,
    TRIP_PUBLISHED,
    /** Demande d'envoi publiée. */
    REQUEST_PUBLISHED,
    /** Identité vérifiée. */
    KYC_VERIFIED,

    // ── Recherche et intérêt ───────────────────────────────────────────────
    SEARCH_NO_TRIP_RESULTS,
    SEARCH_NO_PARCEL_RESULTS,
    /** Résultats > 0 et ≤ {@code fewResultsMax}. */
    SEARCH_FEW_RESULTS,
    /** Trajet ou alerte ajouté aux favoris. */
    FAVORITE_ADDED,
    /** Formulaire de trajet ou de demande quitté sans publier, après l'avoir commencé. */
    FORM_ABANDONED,
    /** Même trajet consulté {@code tripViewedTimes} fois (compteur local de l'app). */
    TRIP_VIEWED_REPEATEDLY,

    // ── Paiement et compte ─────────────────────────────────────────────────
    /** Paiement échoué ou annulé par la personne. */
    PAYMENT_FAILED,
    /** Première ouverture après un changement de version (jamais à l'installation). */
    APP_UPDATED,

    // ── Étapes du colis (toujours à la fin : les ordinaux d'avant ne bougent pas) ──
    // L'app les déclenche à l'ouverture du détail du colis à ce statut, ou à l'ouverture de l'app après la
    // notification de l'étape. Elles exigent caps=moments ET caps=parcel.
    /** Offre acceptée (statut ACCEPTED). */
    PARCEL_ACCEPTED,
    /** Colis remis au voyageur (statut HANDED_OVER). */
    PARCEL_HANDED_OVER,
    /** Colis en voyage (statut IN_TRANSIT). */
    PARCEL_IN_TRANSIT,
    /** Colis arrivé dans la ville de destination (statut ARRIVED). */
    PARCEL_ARRIVED,
    /** Colis livré (statut COMPLETED). */
    PARCEL_DELIVERED;

    /** Vrai pour les déclencheurs que les apps sans {@code caps=moments} ne savent pas déclencher. */
    public boolean needsMomentsCapability() {
        return ordinal() >= DELIVERY_CONFIRMED.ordinal();
    }

    /** Vrai pour les étapes du colis ({@code PARCEL_*}), qui exigent en plus {@code caps=parcel}. */
    public boolean isParcelStep() {
        return switch (this) {
            case PARCEL_ACCEPTED, PARCEL_HANDED_OVER, PARCEL_IN_TRANSIT, PARCEL_ARRIVED, PARCEL_DELIVERED -> true;
            default -> false;
        };
    }

    /**
     * Vrai pour les déclencheurs auxquels le ciblage par corridor s'applique : recherche, favoris,
     * consultation répétée, formulaire abandonné, publication de trajet ou de demande.
     */
    public boolean usesCorridors() {
        return switch (this) {
            case SEARCH_NO_TRIP_RESULTS, SEARCH_NO_PARCEL_RESULTS, SEARCH_FEW_RESULTS, FAVORITE_ADDED,
                 FORM_ABANDONED, TRIP_VIEWED_REPEATEDLY, TRIP_PUBLISHED, REQUEST_PUBLISHED -> true;
            default -> false;
        };
    }
}
