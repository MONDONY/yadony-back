package com.yadony.api.matching;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Fenêtre de contact direct entre expéditeur et voyageur (appel in-app et bouton téléphone).
 *
 * <p>Source unique de la règle : ouverte de l'acceptation jusqu'à l'arrivée, puis après la
 * livraison confirmée tant que {@code deliveredAt + graceDays} n'est pas atteint, pour un
 * éventuel litige (FLUTTER-DK). Un colis livré sans date de livraison connue est hors fenêtre.
 * Lue par {@code calls.CallEligibilityService} et par {@link BidService} ({@code contactWindowOpen}).
 */
public final class ContactWindow {

    /** Statuts où le contact est ouvert sans condition de date. */
    public static final Set<BidStatus> OPEN_STATUSES = EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

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
}
