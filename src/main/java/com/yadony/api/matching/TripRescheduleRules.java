package com.yadony.api.matching;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

/**
 * Règles du report de trajet partagées entre le report (matching) et la réponse de
 * l'expéditeur (cancellation).
 */
public final class TripRescheduleRules {

    /** Deux reports au plus : au-delà, le voyageur annule et republie (CHECK V278). */
    public static final int MAX_RESCHEDULES = 2;

    /**
     * Colis dont l'expéditeur doit répondre au report : accepté (pas encore remis) ou
     * déjà remis au voyageur mais pas encore parti.
     */
    public static final Set<BidStatus> DECISION_STATUSES = EnumSet.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER);

    /** Demandes pas encore acceptées : leur expéditeur est simplement prévenu. */
    public static final Set<BidStatus> INFORMED_STATUSES =
            EnumSet.of(BidStatus.AWAITING_PAYMENT, BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED);

    /**
     * Colis qui interdisent tout report : en route, arrivé à destination ou déjà livré.
     * Le voyage a eu lieu, le reporter n'aurait plus de sens (FLUTTER-BD).
     */
    public static final Set<BidStatus> BLOCKING_STATUSES =
            EnumSet.of(BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED);

    private TripRescheduleRules() {}

    /** Reports encore possibles, affichés au voyageur avant qu'il n'essaie. */
    public static int remaining(AnnouncementEntity announcement) {
        return Math.max(0, MAX_RESCHEDULES - announcement.getRescheduleCount());
    }

    /**
     * Jusqu'à quand l'expéditeur peut se retirer, <b>en UTC</b> (convention des
     * {@code LocalDateTime} du projet : l'app reçoit l'heure suffixée « Z »). Colis accepté :
     * la nouvelle date limite de remise, déjà en UTC (après, il doit remettre le colis).
     * Colis déjà remis : le nouveau départ, heure murale du fuseau du trajet ramenée en UTC.
     * Sans réponse, le colis reste sur le trajet. Mélanger les deux repères décalait la
     * décision du décalage horaire (YADONY-FLUTTER-PROD-1R).
     */
    public static LocalDateTime decisionDeadline(BidEntity bid, AnnouncementEntity announcement) {
        return decisionDeadline(bid, bid.getStatus(), announcement);
    }

    private static LocalDateTime decisionDeadline(BidEntity bid, BidStatus status, AnnouncementEntity announcement) {
        if (status == BidStatus.ACCEPTED && bid.getHandoverDeadline() != null) {
            return bid.getHandoverDeadline();
        }
        LocalTime time = announcement.getDepartureTime() != null
                ? announcement.getDepartureTime() : LocalTime.of(23, 59);
        return announcement.getDepartureDate().atTime(time)
                .atZone(TripTimezones.zoneOf(announcement.getTimezone()))
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    /** L'expéditeur peut-il encore répondre au report en attente sur ce colis ? */
    public static boolean decisionOpen(BidEntity bid, AnnouncementEntity announcement) {
        return decisionOpen(bid, bid.getStatus(), announcement, Instant.now());
    }

    /**
     * Même règle, le colis pris dans le statut {@code status} à l'instant {@code now} : celui d'avant son annulation,
     * que l'annulation vient de remplacer sur l'entité (frais d'annulation de l'expéditeur, {@code cancellation/fee} :
     * aucun frais tant que l'expéditeur peut encore répondre au report).
     */
    public static boolean decisionOpen(BidEntity bid, BidStatus status, AnnouncementEntity announcement, Instant now) {
        if (bid.getPendingRescheduleId() == null || announcement == null || !DECISION_STATUSES.contains(status)) {
            return false;
        }
        return now.isBefore(decisionDeadline(bid, status, announcement).toInstant(ZoneOffset.UTC));
    }
}
