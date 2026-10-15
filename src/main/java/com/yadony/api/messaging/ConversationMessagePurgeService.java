package com.yadony.api.messaging;

import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Purge des échanges 3 jours après la livraison du colis : messages Firestore (avec leur
 * historique de modification) et photos R2 de chaque conversation du bid, expéditeur comme
 * destinataire. La conversation reste listée, vide, et n'accepte plus de message.
 *
 * <ul>
 *   <li>délai : {@code yadony.messaging.purge-after-delivery-days} (3 par défaut), même
 *       fenêtre que les appels et l'envoi de photos ({@code ContactWindow}) ;</li>
 *   <li>exception : rien n'est effacé tant qu'un litige ou un signalement est ouvert
 *       ({@link com.yadony.api.common.MessagingMediaRetentionHold}, vérifié par
 *       {@link MessagingImageRetentionService#purgeConversation}) ; la purge suit à la
 *       passe qui suit sa clôture ;</li>
 *   <li>un échec R2 ou Firestore laisse {@code messages_purged_at} vide : la prochaine passe
 *       réessaie.</li>
 * </ul>
 */
@Service
public class ConversationMessagePurgeService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMessagePurgeService.class);

    private final ConversationRepository conversationRepository;
    private final MessagingImageRetentionService imageRetention;
    private final FirestoreService firestoreService;
    private final AuditService auditService;
    private final int purgeAfterDays;
    private final Clock clock;

    @Autowired
    public ConversationMessagePurgeService(ConversationRepository conversationRepository,
                                           MessagingImageRetentionService imageRetention,
                                           FirestoreService firestoreService,
                                           AuditService auditService,
                                           @Value("${yadony.messaging.purge-after-delivery-days:3}") int purgeAfterDays) {
        this(conversationRepository, imageRetention, firestoreService, auditService, purgeAfterDays,
                Clock.systemUTC());
    }

    ConversationMessagePurgeService(ConversationRepository conversationRepository,
                                    MessagingImageRetentionService imageRetention,
                                    FirestoreService firestoreService,
                                    AuditService auditService,
                                    int purgeAfterDays,
                                    Clock clock) {
        this.conversationRepository = conversationRepository;
        this.imageRetention = imageRetention;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
        this.purgeAfterDays = purgeAfterDays;
        this.clock = clock;
    }

    public int purgeAfterDays() {
        return purgeAfterDays;
    }

    /** Passe planifiée : purge chaque conversation échue. Rend le nombre de conversations purgées. */
    public int purgeDue() {
        LocalDateTime now = now();
        int purged = 0;
        for (ConversationEntity conv : conversationRepository.findMessagesToPurge(now.minusDays(purgeAfterDays))) {
            try {
                if (purge(conv, now)) {
                    purged++;
                }
            } catch (Exception e) {
                log.warn("ConversationMessagePurge: purge de {} en échec : {}", conv.getId(), e.toString());
            }
        }
        if (purged > 0) {
            log.info("ConversationMessagePurge: {} conversation(s) purgée(s)", purged);
        }
        return purged;
    }

    boolean purge(ConversationEntity conv, LocalDateTime now) {
        // Photos d'abord : faux si une procédure ouverte retient la conversation ou si R2 échoue.
        if (!imageRetention.purgeConversation(conv, null)) {
            return false;
        }
        if (!firestoreService.purgeMessages(conv.getFirestoreConversationId())) {
            return false;
        }
        conv.markMessagesPurged(now);
        conversationRepository.save(conv);
        auditService.log("conversation", conv.getId(), "CONVERSATION_MESSAGES_PURGED", null,
                Map.of("bidId", String.valueOf(conv.getBidId()), "reason", "DELIVERY_RETENTION_EXPIRED",
                        "days", purgeAfterDays));
        return true;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}
