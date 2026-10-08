package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Règle unique « la date limite de dépôt du trajet est-elle passée ? », côté gardes
 * (FLUTTER-GA).
 *
 * <p>Jusqu'ici, seule la création d'une demande la vérifiait ({@link BidService#assertCanBidOn}) :
 * une demande créée la veille pouvait encore être acceptée, payée ou renégociée après la
 * date limite, et le colis naissait « fenêtre de remise dépassée ». Chaque portail qui
 * engage de l'argent ou fait avancer une demande non encore acceptée appelle désormais
 * {@link #assertNotPassed}, et {@link HandoverDeadlineExpiryScheduler} éteint ce qui reste.
 *
 * <p>Le calcul lui-même vit dans {@link AnnouncementEntity#isHandoverDeadlinePassed} (fuseau
 * du trajet). Sans date limite (annonces antérieures à V207), rien n'est jamais bloqué.
 * Chaque étape d'un voyage à plusieurs étapes est une annonce à part entière, avec sa propre
 * date limite : la règle s'applique étape par étape.
 */
public final class HandoverDeadlineRules {

    /** Code RFC 7807 renvoyé en 409, déjà connu de l'app depuis la garde de création. */
    public static final String PROBLEM_CODE = "handover-deadline-passed";

    /** Motif technique des expirations automatiques, repris dans l'audit et les événements. */
    public static final String EXPIRY_REASON = "HANDOVER_DEADLINE_PASSED";

    /**
     * Demandes qui n'engagent encore personne et que la date limite éteint : en attente de
     * réponse du voyageur (PENDING, ou PAYMENT_ESCROWED pour la carte, payée mais pas
     * acceptée), en attente de paiement, et fils de négociation ouverts.
     *
     * <p>ACCEPTED et au-delà en sont exclus volontairement : un colis accepté (et payé) se
     * règle par le parcours de non-présentation, jamais par une annulation automatique.
     */
    public static final Set<BidStatus> EXPIRABLE_STATUSES = EnumSet.of(
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.AWAITING_PAYMENT,
            BidStatus.NEGOTIATING);

    /**
     * Plus grand décalage horaire en avance sur UTC (UTC+14). La date limite est une heure
     * murale du fuseau du trajet : une requête en UTC qui prend {@code now + 14 h} comme borne
     * ne manque aucun trajet, le tri fin se fait ensuite trajet par trajet.
     */
    static final long MAX_ZONE_AHEAD_HOURS = 14;

    private HandoverDeadlineRules() {}

    /**
     * Instant réel d'une date limite de dépôt : heure murale du fuseau du trajet
     * ({@link TripTimezones#zoneOf}, Europe/Paris si absent ou invalide), jamais de l'UTC.
     * Même règle que {@link AnnouncementEntity#isHandoverDeadlinePassed}.
     */
    public static Instant deadlineInstant(LocalDateTime wallClockDeadline, String timezone) {
        return wallClockDeadline.atZone(TripTimezones.zoneOf(timezone)).toInstant();
    }

    public static void assertNotPassed(AnnouncementEntity announcement) {
        assertNotPassed(announcement, Instant.now());
    }

    public static void assertNotPassed(AnnouncementEntity announcement, Instant now) {
        if (announcement != null && announcement.isHandoverDeadlinePassed(now)) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, PROBLEM_CODE, "Handover Deadline Passed",
                    "La date limite de remise des colis pour ce trajet est passée");
        }
    }
}
