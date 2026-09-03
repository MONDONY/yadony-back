package com.yadony.api.notifications;

import java.util.Set;

/**
 * Famille d'une notification, telle que l'app la range.
 *
 * <p>Le critère est le comportement, pas le domaine : colis, trajets et paiements
 * sont urgents et actionnables, donc fusionnés par date dans le feed. Seule
 * {@link #ANNONCE} en sort, dans une boîte à part : une annonce plateforme n'est
 * ni urgente ni courte, et son texte n'existe nulle part ailleurs que dans la
 * notification elle-même (d'où {@code fullBody}).
 *
 * <p>Un type inconnu retombe sur {@link #COLIS} : il reste visible dans le feed.
 * Le pire serait qu'un nouveau type disparaisse dans la boîte des annonces.
 *
 * <p>La migration {@code V238} porte la même table, en SQL, pour le backfill.
 * Toute modification ici doit y être reportée pour les lignes déjà en base.
 */
public enum NotificationCategory {

    COLIS("colis"),
    TRAJETS("trajets"),
    PAIEMENTS("paiements"),
    ANNONCE("annonce");

    private static final Set<String> ANNONCES = Set.of(
            "ADMIN_BROADCAST", "SYSTEM", "ADMIN_WARNING", "MESSAGING_MUTED");

    private static final Set<String> PAIEMENTS_TYPES = Set.of(
            "PAYMENT_RELEASED", "MM_PAYMENT_PENDING", "MOBILE_MONEY_PAYMENT_CONFIRMED",
            "KYC_VERIFIED", "KYC_ACTION_REQUIRED", "KYC_RESET",
            "STRIPE_ONBOARDING_INCOMPLETE", "CARD_EXPIRING",
            "negotiation_awaiting_payment", "negotiation_commission_pending",
            "negotiation_commission_declined", "negotiation_commission_expired");

    private static final Set<String> TRAJETS_TYPES = Set.of(
            "TRIP_IN_PROGRESS", "CORRIDOR_ALERT", "TRAVELER_NEW_ANNOUNCEMENT",
            "PACKAGE_MATCH", "TRAVELER_INVITE", "ANNOUNCEMENT_REMOVED",
            "automation_capacity_free", "automation_loyal_sender",
            "negotiation", "negotiation_started", "negotiation_counter",
            "negotiation_expired", "negotiation_awaiting_trip", "negotiation_trip_changed",
            "request_accepted", "request_expired");

    private final String code;

    NotificationCategory(String code) {
        this.code = code;
    }

    /** Valeur servie par l'API ({@code colis}, {@code trajets}, {@code paiements}, {@code annonce}). */
    public String code() {
        return code;
    }

    public static NotificationCategory fromType(String type) {
        if (type == null || type.isBlank()) return COLIS;
        if (ANNONCES.contains(type)) return ANNONCE;
        if (PAIEMENTS_TYPES.contains(type)) return PAIEMENTS;
        if (TRAJETS_TYPES.contains(type)) return TRAJETS;
        return COLIS;
    }
}
