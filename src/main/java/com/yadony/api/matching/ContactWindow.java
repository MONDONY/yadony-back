package com.yadony.api.matching;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Fenêtre de contact direct entre expéditeur et voyageur (appel in-app et bouton téléphone).
 *
 * <p>Source unique de la règle : ouverte de l'acceptation jusqu'à l'arrivée, puis après la
 * livraison confirmée tant que {@code deliveredAt + graceDays} n'est pas atteint, pour un
 * éventuel litige (FLUTTER-DK), et pendant le retour d'un colis annulé après sa remise
 * (FLUTTER-FM), et {@link #AFTER_CANCELLATION} après l'annulation d'une commande en cours
 * (FLUTTER-P1). Un colis livré sans date de livraison connue est hors fenêtre.
 * Lue par {@code calls.CallEligibilityService} et par {@link BidService} ({@code contactWindowOpen}).
 */
public final class ContactWindow {

    /** Statuts où le contact est ouvert sans condition de date. */
    public static final Set<BidStatus> OPEN_STATUSES = EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

    /**
     * Durée pendant laquelle expéditeur et voyageur peuvent encore s'appeler et voir le numéro
     * de l'autre après l'annulation d'une commande en cours (FLUTTER-P1) : régler la suite
     * (remboursement, malentendu, nouveau rendez-vous) sans repasser par le support.
     */
    public static final Duration AFTER_CANCELLATION = Duration.ofHours(48);

    private ContactWindow() {}

    /**
     * @param deliveredAt première confirmation de livraison, en UTC (V285)
     * @param graceDays   jours de contact après la livraison ({@code yadony.calls.delivery-grace-days})
     * @param nowUtc      instant présent, en UTC
     */
    public static boolean isOpen(BidStatus status, LocalDateTime deliveredAt, int graceDays, LocalDateTime nowUtc) {
        if (status == null) return false;
        if (OPEN_STATUSES.contains(status)) return true;
        if (status != BidStatus.COMPLETED || deliveredAt == null) return false;
        return nowUtc.isBefore(deliveredAt.plusDays(graceDays));
    }

    /**
     * Fenêtre complète d'un bid : celle du statut ({@link #isOpen(BidStatus, LocalDateTime, int,
     * LocalDateTime)}), plus le retour d'un colis annulé après sa remise ({@link #isReturnInProgress}).
     */
    public static boolean isOpen(BidEntity bid, int graceDays, LocalDateTime nowUtc) {
        if (bid == null) return false;
        return isOpen(bid.getStatus(), bid.getDeliveredAt(), graceDays, nowUtc)
                || isReturnInProgress(bid, nowUtc)
                || isRecentlyCancelled(bid, nowUtc);
    }

    /**
     * Commande annulée il y a moins de {@link #AFTER_CANCELLATION} (FLUTTER-P1), alors qu'elle
     * était en cours ({@link #OPEN_STATUSES} : le contact était déjà ouvert). Une demande annulée
     * avant son acceptation n'ouvre rien : les parties n'avaient jamais eu le contact. Borne
     * exclusive : à {@code cancelledAt + 48 h} pile, le contact est fermé. Les annulations
     * antérieures à V316 (sans horodatage) restent fermées. L'absence confirmée de l'expéditeur
     * ({@link #isClosedBySenderNoShow}) n'ouvre rien non plus (FLUTTER-QQ).
     */
    public static boolean isRecentlyCancelled(BidEntity bid, LocalDateTime nowUtc) {
        return bid != null
                && bid.getStatus() == BidStatus.CANCELLED
                && !isClosedBySenderNoShow(bid)
                && bid.getCancelledAt() != null
                && bid.getCancelledFromStatus() != null
                && OPEN_STATUSES.contains(bid.getCancelledFromStatus())
                && nowUtc.isBefore(bid.getCancelledAt().plus(AFTER_CANCELLATION));
    }

    /**
     * Colis annulé parce que l'absence de l'expéditeur à la remise a été confirmée (FLUTTER-QQ) :
     * appels et numéro coupés tout de suite, conversation en lecture seule pour les deux parties.
     * Pendant les 24 h de contestation, le colis reste ACCEPTED et tout reste ouvert.
     */
    public static boolean isClosedBySenderNoShow(BidEntity bid) {
        return bid != null
                && bid.getStatus() == BidStatus.CANCELLED
                && BidEntity.REJECTION_SENDER_NO_SHOW.equals(bid.getRejectionReason());
    }

    /**
     * Retour en cours (FLUTTER-FM) : le colis, annulé alors que le voyageur l'avait déjà, doit
     * revenir à l'expéditeur. Le contact reste ouvert tant que le délai de retour court et que
     * la restitution n'est pas confirmée, sans quoi l'expéditeur ne pouvait plus joindre le
     * voyageur pour récupérer son colis. Se ferme à la restitution ou à l'échéance (l'équipe
     * prend alors le relais, cf. {@code ReturnDeadlineScheduler}).
     */
    public static boolean isReturnInProgress(BidEntity bid, LocalDateTime now) {
        return bid != null
                && bid.getStatus() == BidStatus.CANCELLED
                && bid.getReturnDeadline() != null
                && bid.getReturnedAt() == null
                && now.isBefore(bid.getReturnDeadline());
    }

    /**
     * Numéro de la contrepartie communicable : statuts {@link BidStatus#PHONE_VISIBLE_STATUSES},
     * retour du colis en cours, ou annulation d'une commande en cours depuis moins de 48 h.
     */
    public static boolean phoneVisible(BidEntity bid, LocalDateTime now) {
        return bid != null
                && (BidStatus.PHONE_VISIBLE_STATUSES.contains(bid.getStatus())
                    || isReturnInProgress(bid, now)
                    || isRecentlyCancelled(bid, now));
    }
}
