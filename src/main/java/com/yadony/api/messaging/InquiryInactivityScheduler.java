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
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
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
        if (isHeld(conv)) {
            return Outcome.SKIPPED;
        }
        String firestoreId = conv.getFirestoreConversationId();
        boolean hasFirestore = firestoreId != null && !firestoreId.isBlank();
        if (hasFirestore) {
            Instant lastMessage = firestoreService.lastMessageInstant(firestoreId).orElse(null);
            if (lastMessage != null) {
                LocalDateTime last = LocalDateTime.ofInstant(lastMessage, ZoneOffset.UTC);
                if (last.isAfter(cutoff)) {
                    conversationRepository.markInquiryActivity(conv.getId(), last);
                    return Outcome.SKIPPED;
                }
            }
        }
        if (conversationRepository.reserveStaleInquiry(conv.getId(), now, cutoff) == 0) {
            return Outcome.SKIPPED;
        }
        if (hasFirestore && !firestoreService.purgeInquiryConversation(firestoreId)) {
            release(conv);
            return Outcome.FAILED;
        }
        auditService.log("conversation", conv.getId(), "INQUIRY_PURGED", null,
                Map.of("announcementId", String.valueOf(conv.getAnnouncementId()),
                        "reason", "INACTIVITY",
                        "days", InquiryPolicy.INACTIVITY_DAYS));
        return Outcome.PURGED;
    }

    private boolean isHeld(ConversationEntity conv) {
        for (MessagingMediaRetentionHold hold : holds) {
            if (hold.holds(null, List.of(conv.getId()))) {
                return true;
            }
        }
        return false;
    }

    private void release(ConversationEntity conv) {
        try {
            conversationRepository.releaseInquiryReservation(conv.getId());
        } catch (Exception e) {
            log.warn("InquiryInactivity: annulation de la réservation de {} en échec, la ligne reste "
                    + "supprimée : {}", conv.getId(), e.toString());
        }
    }
}
