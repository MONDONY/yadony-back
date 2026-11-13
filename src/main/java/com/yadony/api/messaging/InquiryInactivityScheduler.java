package com.yadony.api.messaging;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Nettoyage quotidien des conversations de questions ({@link ConversationKind#INQUIRY}) sans
 * activité depuis {@link InquiryPolicy#INACTIVITY_DAYS} jours : suppression logique en base et
 * effacement Firestore (conversation, messages ET compteur {@code inquiryQuota}, ensemble).
 *
 * <p>Pour chaque conversation sélectionnée :</p>
 * <ol>
 *   <li>saut si une procédure ouverte la retient (même garde que la purge des échanges ;
 *       {@code bidId} nul, seuls les signalements de message ouverts comptent) ;</li>
 *   <li>saut, et {@code last_activity_at} rafraîchi, si Firestore montre un message plus récent
 *       que la limite (le notify qui date l'activité est au mieux-effort) ;</li>
 *   <li>réservation par UPDATE gardé ({@code kind = INQUIRY}, non supprimée, toujours inactive) :
 *       0 ligne = promue, supprimée ou réveillée entre-temps, rien n'est touché, Firestore
 *       compris ; aucune entité périmée n'est réécrite ;</li>
 *   <li>nouvelle vérification (gel, activité) une fois réservée, avant tout effacement ;</li>
 *   <li>effacement Firestore ; en cas d'échec la réservation est annulée et la passe suivante
 *       réessaie (si l'annulation échoue, la ligne reste supprimée : l'effacement Firestore
 *       rejoué ou la conversation fantôme restent sans conséquence pour les utilisateurs).</li>
 * </ol>
 * Un échec sur une conversation n'arrête pas les autres. Idempotente.
 */
@Component
public class InquiryInactivityScheduler {

    private static final Logger log = LoggerFactory.getLogger(InquiryInactivityScheduler.class);

    private enum Outcome { PURGED, SKIPPED, FAILED }

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final AuditService auditService;
    private final List<MessagingMediaRetentionHold> holds;
    private final Clock clock;

    @Autowired
    public InquiryInactivityScheduler(ConversationRepository conversationRepository,
                                      FirestoreService firestoreService,
                                      AuditService auditService,
                                      List<MessagingMediaRetentionHold> holds) {
        this(conversationRepository, firestoreService, auditService, holds, Clock.systemUTC());
    }

    InquiryInactivityScheduler(ConversationRepository conversationRepository,
                               FirestoreService firestoreService,
                               AuditService auditService,
                               List<MessagingMediaRetentionHold> holds,
                               Clock clock) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
        this.holds = holds;
        this.clock = clock;
    }

    @Scheduled(cron = "${yadony.messaging.inquiry-purge-cron:0 20 3 * * *}", zone = "UTC")
    public void scheduledPurge() {
        purgeStaleInquiries();
    }

    /** Rend le nombre de conversations supprimées. */
    public int purgeStaleInquiries() {
        // Micros : précision de la colonne, pour que l'annulation retrouve sa propre réservation.
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime cutoff = now.minusDays(InquiryPolicy.INACTIVITY_DAYS);
        int purged = 0;
        for (ConversationEntity conv : conversationRepository.findStaleInquiries(cutoff)) {
            try {
                if (purge(conv, now, cutoff) == Outcome.PURGED) {
                    purged++;
                }
            } catch (Exception e) {
                log.warn("InquiryInactivity: purge de {} en échec : {}", conv.getId(), e.toString());
            }
        }
        if (purged > 0) {
            log.info("InquiryInactivity: {} conversation(s) de questions supprimée(s)", purged);
        }
        return purged;
    }

    private Outcome purge(ConversationEntity conv, LocalDateTime now, LocalDateTime cutoff) {
        if (isHeld(conv) || keepBecauseActive(conv, cutoff)) {
            return Outcome.SKIPPED;
        }
        if (conversationRepository.reserveStaleInquiry(conv.getId(), now, cutoff) == 0) {
            return Outcome.SKIPPED;
        }
        // Réservée : un gel ou un message apparu depuis les premiers contrôles annule la purge,
        // avant tout effacement Firestore.
        try {
            if (isHeld(conv) || keepBecauseActive(conv, cutoff)) {
                release(conv, now);
                return Outcome.SKIPPED;
            }
            String firestoreId = conv.getFirestoreConversationId();
            if (firestoreId != null && !firestoreId.isBlank()
                    && !firestoreService.purgeInquiryConversation(firestoreId)) {
                release(conv, now);
                return Outcome.FAILED;
            }
        } catch (Exception e) {
            release(conv, now);
            log.warn("InquiryInactivity: purge de {} annulée : {}", conv.getId(), e.toString());
            return Outcome.FAILED;
        }
        auditService.log("conversation", conv.getId(), "INQUIRY_PURGED", null,
                Map.of("announcementId", String.valueOf(conv.getAnnouncementId()),
                        "reason", "INACTIVITY",
                        "days", InquiryPolicy.INACTIVITY_DAYS));
        return Outcome.PURGED;
    }

    /**
     * Firestore montre un message plus récent que la limite (le notify qui date l'activité est au
     * mieux-effort) : à garder, {@code last_activity_at} rafraîchi. Une date illisible vaut « à
     * garder » (jamais « inactive »).
     */
    private boolean keepBecauseActive(ConversationEntity conv, LocalDateTime cutoff) {
        String firestoreId = conv.getFirestoreConversationId();
        if (firestoreId == null || firestoreId.isBlank()) {
            return false;
        }
        try {
            Instant lastMessage = firestoreService.lastMessageInstant(firestoreId).orElse(null);
            if (lastMessage == null) {
                return false;
            }
            LocalDateTime last = LocalDateTime.ofInstant(lastMessage, ZoneOffset.UTC);
            if (!last.isAfter(cutoff)) {
                return false;
            }
            conversationRepository.markInquiryActivity(conv.getId(), last);
            return true;
        } catch (FirestoreService.UnreadableTimestampException e) {
            log.warn("InquiryInactivity: {} conservée, {}", conv.getId(), e.getMessage());
            return true;
        }
    }

    private boolean isHeld(ConversationEntity conv) {
        for (MessagingMediaRetentionHold hold : holds) {
            if (hold.holds(null, List.of(conv.getId()))) {
                return true;
            }
        }
        return false;
    }

    private void release(ConversationEntity conv, LocalDateTime reservedAt) {
        try {
            conversationRepository.releaseInquiryReservation(conv.getId(), reservedAt);
        } catch (Exception e) {
            log.warn("InquiryInactivity: annulation de la réservation de {} en échec, la ligne reste "
                    + "supprimée : {}", conv.getId(), e.toString());
        }
    }
}
