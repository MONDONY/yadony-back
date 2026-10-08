package com.yadony.api.notifications;

/**
 * Contrat {@code GET} / {@code PUT /notifications/preferences}.
 *
 * <p>Les six premiers champs sont des {@code boolean} primitifs, historiques : toutes les
 * versions de l'application les envoient. Les trois derniers (V305, FLUTTER-GB) sont des
 * {@link Boolean} : une application antérieure ne les connaît pas et ne les envoie pas.
 * En écriture, {@code null} signifie « inchangé », jamais « coupé » — sans quoi une
 * ancienne version couperait en silence des réglages que l'utilisateur n'a pas vus.
 * En lecture, ils sont toujours renseignés.
 *
 * <p>{@code pushTripReminder} n'est plus lu par aucun type (voir
 * {@code NotificationPrefsService}) mais reste dans le contrat pour les anciennes versions.
 */
public record NotificationPrefsDto(
    boolean pushActivityBids,
    boolean pushActivityNegotiations,
    boolean pushMessages,
    boolean pushTripReminder,
    boolean pushPromo,
    boolean pushCorridorAlerts,
    Boolean pushMissedCalls,
    Boolean pushTravelerAutomations,
    Boolean pushRemindersTips
) {
    /** Forme historique à six champs : les réglages V305 restent inchangés. */
    public NotificationPrefsDto(boolean pushActivityBids, boolean pushActivityNegotiations,
                                boolean pushMessages, boolean pushTripReminder,
                                boolean pushPromo, boolean pushCorridorAlerts) {
        this(pushActivityBids, pushActivityNegotiations, pushMessages, pushTripReminder,
                pushPromo, pushCorridorAlerts, null, null, null);
    }

    public static NotificationPrefsDto defaults() {
        return new NotificationPrefsDto(true, true, true, true, false, true, true, true, true);
    }
}
