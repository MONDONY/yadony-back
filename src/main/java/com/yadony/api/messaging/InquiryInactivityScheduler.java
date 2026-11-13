package com.yadony.api.messaging;

import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Nettoyage quotidien des conversations de questions ({@link ConversationKind#INQUIRY}) sans
 * activité depuis {@link InquiryPolicy#INACTIVITY_DAYS} jours : suppression logique en base et
 * effacement Firestore (conversation, messages ET compteur {@code inquiryQuota}, ensemble).
 *
 * <p>Idempotente : une conversation supprimée sort de la sélection. Firestore d'abord, base
 * ensuite : un échec Firestore laisse la ligne non supprimée et la passe suivante réessaie (un
 * effacement Firestore rejoué est sans effet). Un échec sur une conversation n'arrête pas les
 * autres. Une conversation promue (SENDER_TRAVELER) n'est jamais sélectionnée.</p>
 */
@Component
public class InquiryInactivityScheduler {

    private static final Logger log = LoggerFactory.getLogger(InquiryInactivityScheduler.class);

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public InquiryInactivityScheduler(ConversationRepository conversationRepository,
                                      FirestoreService firestoreService,
                                      AuditService auditService) {
        this(conversationRepository, firestoreService, auditService, Clock.systemUTC());
    }

    InquiryInactivityScheduler(ConversationRepository conversationRepository,
                               FirestoreService firestoreService,
                               AuditService auditService,
                               Clock clock) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Scheduled(cron = "${yadony.messaging.inquiry-purge-cron:0 20 3 * * *}", zone = "UTC")
    public void scheduledPurge() {
        purgeStaleInquiries();
    }

    /** Rend le nombre de conversations supprimées. */
    public int purgeStaleInquiries() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        int purged = 0;
        for (ConversationEntity conv
                : conversationRepository.findStaleInquiries(now.minusDays(InquiryPolicy.INACTIVITY_DAYS))) {
            try {
                if (!firestoreService.purgeInquiryConversation(conv.getFirestoreConversationId())) {
                    continue;
                }
                conv.softDelete();
                conversationRepository.save(conv);
                auditService.log("conversation", conv.getId(), "INQUIRY_PURGED", null,
                        Map.of("announcementId", String.valueOf(conv.getAnnouncementId()),
                                "reason", "INACTIVITY",
                                "days", InquiryPolicy.INACTIVITY_DAYS));
                purged++;
            } catch (Exception e) {
                log.warn("InquiryInactivity: purge de {} en échec : {}", conv.getId(), e.toString());
            }
        }
        if (purged > 0) {
            log.info("InquiryInactivity: {} conversation(s) de questions supprimée(s)", purged);
        }
        return purged;
    }
}
