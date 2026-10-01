package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.reception.BidRecipientLinkEntity;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Conversation séparée voyageur ↔ destinataire d'un colis (lot 3C), invisible pour l'expéditeur.
 *
 * <p>Réutilise la table {@code conversations} et le document Firestore {@code conversations/{id}}
 * avec {@code kind = RECIPIENT_TRAVELER} : {@code sender_id} / {@code senderId} y portent le
 * destinataire, si bien que la Cloud Function des non-lus et les règles Firestore restent
 * inchangées.
 *
 * <p><b>Identifiant Firestore</b> : {@code rconv_<bidId>} pour la première conversation
 * destinataire du colis, puis {@code rconv_<bidId>_<n>} (n = 2, 3…) pour chaque nouveau
 * destinataire après un changement. Le format est opaque pour l'app.
 */
@Service
public class RecipientConversationService {

    private static final Logger log = LoggerFactory.getLogger(RecipientConversationService.class);

    /** Statuts où une conversation destinataire peut naître. */
    static final Set<BidStatus> OPENABLE = EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED);

    static final String FIRESTORE_PREFIX = "rconv_";

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final BidRecipientLinkRepository linkRepository;
    private final BlockVisibility blockVisibility;
    private final Clock clock;

    @Autowired
    public RecipientConversationService(ConversationRepository conversationRepository,
                                        FirestoreService firestoreService,
                                        UserRepository userRepository,
                                        AuditService auditService,
                                        BidRepository bidRepository,
                                        AnnouncementRepository announcementRepository,
                                        BidRecipientLinkRepository linkRepository,
                                        BlockVisibility blockVisibility) {
        this(conversationRepository, firestoreService, userRepository, auditService, bidRepository,
                announcementRepository, linkRepository, blockVisibility, Clock.systemUTC());
    }

    RecipientConversationService(ConversationRepository conversationRepository,
                                 FirestoreService firestoreService,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 BidRepository bidRepository,
                                 AnnouncementRepository announcementRepository,
                                 BidRecipientLinkRepository linkRepository,
                                 BlockVisibility blockVisibility,
                                 Clock clock) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.linkRepository = linkRepository;
        this.blockVisibility = blockVisibility;
        this.clock = clock;
    }

    /**
     * Récupère ou crée la conversation destinataire du colis.
     *
     * <p>Autorisé au voyageur de l'annonce et au destinataire, tant qu'un lien CONFIRMED actif
     * les relie au colis. Sinon 403 {@code recipient-conversation-forbidden} ; 404 si le bid
     * n'existe pas. Une conversation existante est rendue quel que soit le statut du colis
     * (l'app la montre en lecture seule une fois le colis terminé, comme l'existant) ; une
     * nouvelle ne naît que sur un colis ACCEPTED → COMPLETED.
     */
    @Transactional
    public ConversationEntity getOrCreate(UUID bidId, UUID callerId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found",
                        "Bid Not Found", "Bid introuvable"));
        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(AnnouncementEntity::getTravelerId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Announcement Not Found", "Annonce introuvable"));

        BidRecipientLinkEntity link = linkRepository.findByBidId(bidId)
                .filter(l -> l.getStatus() == ReceptionLinkStatus.CONFIRMED)
                .orElse(null);
        UUID recipientId = link != null ? link.getRecipientUserId() : null;
        boolean allowed = recipientId != null
                && !recipientId.equals(travelerId)
                && (callerId.equals(travelerId) || callerId.equals(recipientId));
        if (!allowed) {
            throw forbidden();
        }

        blockVisibility.assertVisible(callerId, callerId.equals(travelerId) ? recipientId : travelerId);

        // Une conversation ouverte avec un ancien destinataire (révocation manquée) est
        // fermée au passage : l'accès suit toujours le lien CONFIRMED courant.
        Optional<ConversationEntity> current = Optional.empty();
        for (ConversationEntity open : conversationRepository.findOpenRecipientConversations(bidId)) {
            if (open.participantAId().equals(recipientId) && open.getTravelerId().equals(travelerId)) {
                current = Optional.of(open);
            } else {
                close(open);
            }
        }
        if (current.isPresent()) {
            return current.get();
        }

        if (!OPENABLE.contains(bid.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "recipient-conversation-unavailable",
                    "Recipient Conversation Unavailable",
                    "Ce colis n'est plus en cours : la conversation avec le destinataire ne peut pas s'ouvrir");
        }
        return create(bid, recipientId, travelerId, callerId);
    }

    /**
     * Ferme toute conversation destinataire ouverte du colis dont le destinataire n'a plus de
     * lien CONFIRMED actif (changement de destinataire, lien refusé ou retiré). Idempotent.
     */
    @Transactional
    public int revokeStale(UUID bidId) {
        int closed = 0;
        for (ConversationEntity open : conversationRepository.findOpenRecipientConversations(bidId)) {
            boolean stillConfirmed = linkRepository.existsByBidIdAndRecipientUserIdAndStatus(
                    bidId, open.participantAId(), ReceptionLinkStatus.CONFIRMED);
            if (!stillConfirmed) {
                close(open);
                closed++;
            }
        }
        return closed;
    }

    private void close(ConversationEntity conv) {
        conv.close(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
        conversationRepository.save(conv);
        auditService.log("conversation", conv.getId(), "RECIPIENT_CONVERSATION_CLOSED", null,
                Map.of("bidId", conv.getBidId().toString(),
                        "firestoreId", conv.getFirestoreConversationId()));
        try {
            String revokedUid = userRepository.findByIdIncludingDeleted(conv.participantAId())
                    .map(UserEntity::getFirebaseUid)
                    .orElse(null);
            firestoreService.revokeRecipient(conv.getFirestoreConversationId(), revokedUid);
        } catch (Exception e) {
            // Postgres reste la source de vérité : l'accès API est déjà coupé. Le document
            // sera réécrit sans senderId par ensureFirestoreDocument s'il manque.
            log.warn("Firestore revokeRecipient failed for {}: {}", conv.getFirestoreConversationId(), e.getMessage());
        }
    }

    private ConversationEntity create(BidEntity bid, UUID recipientId, UUID travelerId, UUID callerId) {
        UUID bidId = bid.getId();
        String firestoreId = nextFirestoreId(bidId);

        UserEntity recipient = userRepository.findById(recipientId).orElseThrow(RecipientConversationService::forbidden);
        UserEntity traveler = userRepository.findById(travelerId).orElseThrow(RecipientConversationService::forbidden);

        ConversationEntity saved = conversationRepository.save(
                ConversationEntity.forRecipient(bidId, recipientId, travelerId, firestoreId));

        auditService.log("conversation", saved.getId(), "CONVERSATION_CREATED", callerId,
                Map.of("bidId", bidId.toString(), "firestoreId", firestoreId,
                        "kind", ConversationKind.RECIPIENT_TRAVELER.name()));

        try {
            String now = Instant.now(clock).toString();
            Map<String, Object> data = new HashMap<>();
            data.put("bidId", bidId.toString());
            data.put("kind", ConversationKind.RECIPIENT_TRAVELER.name());
            data.put("senderId", recipient.getFirebaseUid());
            data.put("travelerId", traveler.getFirebaseUid());
            data.put("senderName", recipient.publicDisplayName());
            data.put("travelerName", traveler.publicDisplayName());
            data.put("createdAt", now);
            data.put("lastMessageAt", now);
            data.put("lastMessagePreview", "Connexion établie !");
            firestoreService.createConversation(firestoreId, data);
            firestoreService.addSystemMessage(firestoreId,
                    "Connexion établie ! Voyageur et destinataire peuvent maintenant échanger pour organiser la remise du colis.");
        } catch (Exception e) {
            log.warn("Firestore recipient conversation init failed for bid {}: {}", bidId, e.getMessage());
        }
        return saved;
    }

    /** {@code rconv_<bidId>}, puis {@code rconv_<bidId>_<n>} : jamais un id déjà pris. */
    private String nextFirestoreId(UUID bidId) {
        long n = conversationRepository.countRecipientConversations(bidId);
        String base = FIRESTORE_PREFIX + bidId;
        String candidate = n == 0 ? base : base + "_" + (n + 1);
        while (conversationRepository.findByFirestoreConversationId(candidate).isPresent()) {
            n++;
            candidate = base + "_" + (n + 1);
        }
        return candidate;
    }

    private static YadonyBusinessException forbidden() {
        return new YadonyBusinessException(HttpStatus.FORBIDDEN, "recipient-conversation-forbidden",
                "Forbidden", "Vous n'avez pas accès à la conversation avec le destinataire de ce colis");
    }
}
