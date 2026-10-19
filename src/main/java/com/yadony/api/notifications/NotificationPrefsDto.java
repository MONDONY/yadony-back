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
 * <p>V340 : {@code pushActivityBids} ne couvre plus que l'expéditeur (« Mes envois ») ;
 * {@code pushMyTrips} (voyageur) et {@code pushRecipientParcels} (destinataire) suivent la
 * même règle que les champs V305. Une application qui ne les envoie pas ne connaît que
 * l'ancien « Offres » : son choix vaut alors pour les trois rôles, comme avant.
 *
 * <p>V342 : {@code emailRecaps} et {@code emailAlertsTips} règlent les e-mails coupables
 * ({@link NotificationEmailPolicy}) ; {@code null} en écriture laisse la valeur stockée.
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
    Boolean pushRemindersTips,
    Boolean pushMyTrips,
    Boolean pushRecipientParcels,
    Boolean emailRecaps,
    Boolean emailAlertsTips
) {
    /** Forme historique à six champs : les réglages V305 restent inchangés. */
    public NotificationPrefsDto(boolean pushActivityBids, boolean pushActivityNegotiations,
                                boolean pushMessages, boolean pushTripReminder,
                                boolean pushPromo, boolean pushCorridorAlerts) {
        this(pushActivityBids, pushActivityNegotiations, pushMessages, pushTripReminder,
                pushPromo, pushCorridorAlerts, null, null, null);
    }

    /** Forme V305 à neuf champs : les réglages par rôle (V340) suivent « Offres ». */
    public NotificationPrefsDto(boolean pushActivityBids, boolean pushActivityNegotiations,
                                boolean pushMessages, boolean pushTripReminder,
                                boolean pushPromo, boolean pushCorridorAlerts,
                                Boolean pushMissedCalls, Boolean pushTravelerAutomations,
                                Boolean pushRemindersTips) {
        this(pushActivityBids, pushActivityNegotiations, pushMessages, pushTripReminder,
                pushPromo, pushCorridorAlerts, pushMissedCalls, pushTravelerAutomations,
                pushRemindersTips, null, null);
    }

    /** Forme V340 à onze champs : les réglages e-mail (V342) restent inchangés. */
    public NotificationPrefsDto(boolean pushActivityBids, boolean pushActivityNegotiations,
                                boolean pushMessages, boolean pushTripReminder,
                                boolean pushPromo, boolean pushCorridorAlerts,
                                Boolean pushMissedCalls, Boolean pushTravelerAutomations,
                                Boolean pushRemindersTips, Boolean pushMyTrips,
                                Boolean pushRecipientParcels) {
        this(pushActivityBids, pushActivityNegotiations, pushMessages, pushTripReminder,
                pushPromo, pushCorridorAlerts, pushMissedCalls, pushTravelerAutomations,
                pushRemindersTips, pushMyTrips, pushRecipientParcels, null, null);
    }

    public static NotificationPrefsDto defaults() {
        return new NotificationPrefsDto(true, true, true, true, false, true, true, true, true,
                true, true, true, true);
    }
}
