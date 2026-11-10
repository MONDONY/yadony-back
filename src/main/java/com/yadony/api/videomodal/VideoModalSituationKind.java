package com.yadony.api.videomodal;

/**
 * Situation constatée par le serveur à chaque appel d'éligibilité, pour un compte connecté.
 * {@code unit} dit ce que mesure {@code value} ; {@code defaultValue} est repris si elle est absente.
 */
public enum VideoModalSituationKind {
    /** Offre acceptée mais non payée depuis N heures (expéditeur). */
    OFFER_ACCEPTED_UNPAID(Unit.HOURS, 24),
    /** Offre reçue par un voyageur sans réponse depuis N heures. */
    OFFER_UNANSWERED(Unit.HOURS, 24),
    /** Contre-offre en attente de réponse depuis N heures. */
    COUNTER_OFFER_PENDING(Unit.HOURS, 24),
    /** Identité commencée puis abandonnée (règle {@code kyc_abandoned} de la Diffusion). */
    KYC_ABANDONED(Unit.NONE, null),
    /** Photo, ville ou « À propos » manquant. */
    PROFILE_INCOMPLETE(Unit.NONE, null),
    /** Au moins une livraison réussie et aucune invitation de parrainage. */
    FIRST_DELIVERY_NO_REFERRAL(Unit.NONE, null),
    /** Retour après une absence d'au moins N jours. */
    RETURN_AFTER_ABSENCE(Unit.DAYS, 14),
    /** N jours après l'inscription sans trajet publié, demande ni colis. */
    DAYS_SINCE_INSTALL_NO_ACTION(Unit.DAYS, 7);

    public enum Unit { NONE, HOURS, DAYS }

    public static final int MAX_HOURS = 720;
    public static final int MAX_DAYS = 365;

    private final Unit unit;
    private final Integer defaultValue;

    VideoModalSituationKind(Unit unit, Integer defaultValue) {
        this.unit = unit;
        this.defaultValue = defaultValue;
    }

    public Unit unit() { return unit; }

    public Integer defaultValue() { return defaultValue; }

    public int maxValue() { return unit == Unit.HOURS ? MAX_HOURS : MAX_DAYS; }
}
