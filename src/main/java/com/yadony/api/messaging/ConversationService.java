package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.messaging.dto.ConversationResponse;
import com.yadony.api.messaging.dto.ParticipantDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final StorageService storageService;

    public ConversationService(ConversationRepository conversationRepository,
                                FirestoreService firestoreService,
                                UserRepository userRepository,
                                AuditService auditService,
                                BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                StorageService storageService) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.storageService = storageService;
    }

    @Transactional
    public ConversationEntity getOrCreateByBidId(UUID bidId, UUID requestingUserId) {
        // Return the conversation if it's still visible to the requesting user
        Optional<ConversationEntity> accessible =
            conversationRepository.findByBidIdAndParticipant(bidId, requestingUserId);
        if (accessible.isPresent()) {
            return accessible.get();
        }

        // The requesting user deleted their copy — return it anyway with deletedBySelf flag
        // so the caller can offer a "Restore" option instead of throwing GONE.
        Optional<ConversationEntity> deletedBySelf =
            conversationRepository.findByBidIdAndParticipantIgnoreDeleted(bidId, requestingUserId);
        if (deletedBySelf.isPresent()) {
            return deletedBySelf.get();
        }

        // No conversation yet → create one (verify the user belongs to this bid)
        BidEntity bid = bidRepository.findById(bidId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bid not found"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found"));

        UUID senderId  = bid.getSenderId();
        UUID travelerId = announcement.getTravelerId();

        if (!requestingUserId.equals(senderId) && !requestingUserId.equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied");
        }

        return createConversationForBid(bidId, senderId, travelerId);
    }

    @Transactional
    public ConversationEntity restoreConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreDeleted(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));

        if (!conv.isDeletedByUser(requestingUserId)) {
            return conv; // Nothing to restore
        }

        conv.restoreForUser(requestingUserId);
        conversationRepository.save(conv);

        auditService.log("conversation", conversationId, "CONVERSATION_RESTORED", requestingUserId,
            Map.of("firestoreId", conv.getFirestoreConversationId()));

        // Clear Firestore deletedAt so the other party's read-only state clears on next load
        try {
            firestoreService.clearConversationDeleted(conv.getFirestoreConversationId());
        } catch (Exception e) {
            log.warn("Firestore clearConversationDeleted failed for {}: {}", conversationId, e.getMessage());
        }

        return conv;
    }

    @Transactional
    public ConversationEntity createConversationForBid(UUID bidId, UUID senderId, UUID travelerId) {
        return conversationRepository.findByBidId(bidId).orElseGet(() -> {
            String firestoreId = "conv_" + bidId;

            UserEntity sender   = userRepository.findById(senderId).orElseThrow();
            UserEntity traveler = userRepository.findById(travelerId).orElseThrow();

            ConversationEntity entity = new ConversationEntity(bidId, senderId, travelerId, firestoreId);
            ConversationEntity saved  = conversationRepository.save(entity);

            auditService.log("conversation", saved.getId(), "CONVERSATION_CREATED", senderId,
                Map.of("bidId", bidId.toString(), "firestoreId", firestoreId));

            try {
                String now = Instant.now().toString();
                Map<String, Object> data = Map.of(
                    "bidId",               bidId.toString(),
                    "senderId",            sender.getFirebaseUid(),
                    "travelerId",          traveler.getFirebaseUid(),
                    "senderName",          fullName(sender),
                    "travelerName",        fullName(traveler),
                    "createdAt",           now,
                    "lastMessageAt",       now,
                    "lastMessagePreview",  "Connexion établie !"
                );
                firestoreService.createConversation(firestoreId, data);
                firestoreService.addSystemMessage(firestoreId,
                    "Connexion établie ! Vous pouvez maintenant échanger pour organiser la remise.");
            } catch (Exception e) {
                log.warn("Firestore conversation init failed for bid {}: {}", bidId, e.getMessage());
            }

            return saved;
        });
    }

    /**
     * Recrée le document Firestore d'une conversation s'il manque.
     *
     * <p>Les conversations nées pendant que le bean Firestore était nul n'ont
     * jamais reçu leur document parent : seule leur sous-collection
     * {@code messages} existe. La Cloud Function y lit les participants pour
     * router les non-lus, et le backend y lit l'aperçu du dernier message. Plutôt
     * qu'une migration ponctuelle, chaque conversation se répare à son prochain
     * message — la base reste la source de vérité, Firestore n'en est que le reflet.
     *
     * <p>Ne propage aucune erreur : la réparation est un bonus, jamais une
     * condition de l'envoi d'une notification.
     */
    @Transactional(readOnly = true)
    public void ensureFirestoreDocument(ConversationEntity conv) {
        String firestoreId = conv.getFirestoreConversationId();
        try {
            if (firestoreService.conversationExists(firestoreId)) {
                return;
            }

            UserEntity sender   = userRepository.findById(conv.getSenderId()).orElse(null);
            UserEntity traveler = userRepository.findById(conv.getTravelerId()).orElse(null);
            if (sender == null || traveler == null) {
                log.warn("Firestore document repair skipped for {} — participant missing", firestoreId);
                return;
            }

            Map<String, Object> data = new java.util.HashMap<>();
            data.put("senderId",   sender.getFirebaseUid());
            data.put("travelerId", traveler.getFirebaseUid());
            data.put("senderName",   fullName(sender));
            data.put("travelerName", fullName(traveler));
            data.put("createdAt", conv.getCreatedAt() != null ? conv.getCreatedAt().toString() : Instant.now().toString());
            if (conv.getBidId() != null) {
                data.put("bidId", conv.getBidId().toString());
            }

            firestoreService.createConversation(firestoreId, data);
            log.info("Firestore document repaired for conversation {}", firestoreId);
        } catch (Exception e) {
            log.warn("Firestore document repair failed for {}: {}", firestoreId, e.getMessage());
        }
    }

    @Transactional
    public void deleteConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipant(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));

        conv.deleteForUser(requestingUserId);

        // Les deux parties ont supprimé → purge définitive
        if (conv.getSenderDeletedAt() != null && conv.getTravelerDeletedAt() != null) {
            conversationRepository.delete(conv);
            auditService.log("conversation", conversationId, "CONVERSATION_PURGED", requestingUserId,
                Map.of("firestoreId", conv.getFirestoreConversationId()));
            try {
                firestoreService.purgeConversation(conv.getFirestoreConversationId());
            } catch (Exception e) {
                log.warn("Firestore purgeConversation failed for {}: {}", conversationId, e.getMessage());
            }
            return;
        }

        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_DELETED", requestingUserId,
            Map.of("firestoreId", conv.getFirestoreConversationId()));

        try {
            firestoreService.markConversationDeleted(conv.getFirestoreConversationId());
        } catch (Exception e) {
            log.warn("Firestore markConversationDeleted failed for {}: {}", conversationId, e.getMessage());
        }
    }

    @Transactional
    public void archiveConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreArchived(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));
        conv.archiveForUser(requestingUserId);
        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_ARCHIVED", requestingUserId, Map.of());
    }

    @Transactional
    public void unarchiveConversation(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreArchived(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));
        if (!conv.isArchivedByUser(requestingUserId)) {
            return;
        }
        conv.unarchiveForUser(requestingUserId);
        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_UNARCHIVED", requestingUserId, Map.of());
    }

    public List<ConversationResponse> getArchivedConversations(UUID userId) {
        List<ConversationEntity> archived = conversationRepository
            .findArchivedByParticipant(userId, Pageable.unpaged())
            .getContent();
        Map<String, Map<String, Object>> meta = fetchConversationMeta(
            archived.stream().map(ConversationEntity::getFirestoreConversationId).toList());
        return archived.stream()
            .map(c -> toResponse(c, userId, meta))
            .toList();
    }

    public void updateLastMessage(String firestoreConversationId, String preview) {
        firestoreService.updateLastMessage(firestoreConversationId, preview, Instant.now().toString());
    }

    /**
     * Batch-fetch Firestore lastMessageAt/lastMessagePreview pour une liste de
     * conversations (un seul getAll) — à utiliser avant un {@code page.map(...)}
     * pour éviter un aller-retour Firestore par conversation.
     */
    public Map<String, Map<String, Object>> fetchConversationMeta(List<String> firestoreConversationIds) {
        return firestoreService.getConversationMeta(firestoreConversationIds);
    }

    private static final java.util.Set<BidStatus> PHONE_VISIBLE_STATUSES = java.util.EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.COMPLETED);

    /** Convenience : fait son propre aller-retour Firestore pour une conversation seule. */
    public ConversationResponse toResponse(ConversationEntity conv, UUID currentUserId) {
        Map<String, Object> meta = firestoreService
            .getConversationMeta(List.of(conv.getFirestoreConversationId()))
            .get(conv.getFirestoreConversationId());
        return buildResponse(conv, currentUserId, meta);
    }

    /** Variante batch : réutilise une map déjà chargée (cf. {@link #fetchConversationMeta}). */
    public ConversationResponse toResponse(ConversationEntity conv, UUID currentUserId,
                                            Map<String, Map<String, Object>> metaByFirestoreId) {
        Map<String, Object> meta = metaByFirestoreId.get(conv.getFirestoreConversationId());
        return buildResponse(conv, currentUserId, meta);
    }

    /** Interlocuteur de la conversation, déduit de l'entité sans requête. */
    private static UUID otherUserId(ConversationEntity conv, UUID currentUserId) {
        return conv.getSenderId().equals(currentUserId) ? conv.getTravelerId() : conv.getSenderId();
    }

    private ConversationResponse buildResponse(ConversationEntity conv, UUID currentUserId,
                                                Map<String, Object> meta) {
        UUID otherUserId = otherUserId(conv, currentUserId);

        UserEntity other = userRepository.findById(otherUserId).orElse(null);
        String role = otherUserId.equals(conv.getTravelerId()) ? "Voyageur" : "Expéditeur";

        String tripOrigin      = null;
        String tripDestination = null;
        String tripDate        = null;
        Double tripWeightKg    = null;
        String bidStatus       = null;
        boolean revealPhone    = false;

        Optional<BidEntity> bidOpt = bidRepository.findById(conv.getBidId());
        if (bidOpt.isPresent()) {
            BidEntity bid = bidOpt.get();
            tripWeightKg = bid.getWeightKg() != null ? bid.getWeightKg().doubleValue() : null;
            bidStatus    = mapBidStatus(bid.getStatus());
            // Téléphone révélé seulement quand le deal est actif (même règle que
            // BidService), et jamais si l'intéressé a masqué son numéro dans ses
            // réglages de confidentialité — le chat reste alors son seul canal.
            revealPhone  = PHONE_VISIBLE_STATUSES.contains(bid.getStatus())
                    && other != null && !other.isHidePhoneNumber();

            Optional<AnnouncementEntity> annOpt = announcementRepository.findById(bid.getAnnouncementId());
            if (annOpt.isPresent()) {
                AnnouncementEntity ann = annOpt.get();
                tripOrigin      = ann.getDepartureCity();
                tripDestination = ann.getArrivalCity();
                tripDate        = ann.getDepartureDate() != null ? ann.getDepartureDate().toString() : null;
            }
        }

        ParticipantDTO otherParticipant = buildParticipant(otherUserId, other, revealPhone, role);

        String lastMessagePreview = meta != null ? (String) meta.get("lastMessagePreview") : null;

        return new ConversationResponse(
            conv.getId(),
            conv.getBidId(),
            conv.getFirestoreConversationId(),
            otherParticipant,
            lastMessagePreview,
            parseLastMessageAt(meta, conv.getUpdatedAt()),
            false,  // hasUnread — determined client-side via Firestore
            tripOrigin,
            tripDestination,
            tripDate,
            tripWeightKg,
            bidStatus,
            conv.isReadOnlyFor(currentUserId),
            conv.isDeletedByUser(currentUserId)
        );
    }

    /**
     * lastMessageAt vit dans Firestore (Instant ISO-8601, ex. "2026-07-14T17:03:56.739Z").
     * Retombe sur updated_at Postgres si Firestore est désactivé, la conversation
     * n'a jamais reçu de message, ou le champ est absent/mal formé.
     */
    private static java.time.LocalDateTime parseLastMessageAt(Map<String, Object> meta,
                                                               java.time.LocalDateTime fallback) {
        if (meta == null) {
            return fallback;
        }
        Object raw = meta.get("lastMessageAt");
        if (raw == null) {
            return fallback;
        }
        try {
            return java.time.Instant.parse(raw.toString())
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDateTime();
        } catch (Exception e) {
            return fallback;
        }
    }

    private ParticipantDTO buildParticipant(UUID userId, UserEntity user, boolean revealPhone, String role) {
        if (user == null) {
            return new ParticipantDTO(userId.toString(), "Utilisateur inconnu", null, false, role, false);
        }
        // publicDisplayName() : la concaténation rendait « Utilisateur » pour tout compte
        // sans prénom, si bien que deux interlocuteurs distincts portaient le même nom.
        String name = user.publicDisplayName();
        // Aucun numéro ici : le client reçoit un booléen et demande le numéro au tap
        // (GET /bids/{bidId}/contact). Aucune lecture Firebase au rendu d'une liste.
        boolean kyc = user.getKycStatus() == com.yadony.api.auth.KycStatus.VERIFIED;
        return new ParticipantDTO(userId.toString(), name,
                storageService.avatarUrl(user.getAvatarUrl()), revealPhone, role, kyc);
    }

    private String mapBidStatus(BidStatus status) {
        if (status == null) return null;
        return switch (status) {
            case ACCEPTED -> "BID_ACCEPTED";
            case COMPLETED -> "DELIVERY_CONFIRMED";
            case CANCELLED, NO_SHOW, PARCEL_REFUSED -> "TRIP_CANCELLED";
            default -> null;
        };
    }

    /**
     * Délègue à {@link UserEntity#publicDisplayName()}.
     *
     * <p>Rendait auparavant la chaîne vide pour un compte sans prénom : l'en-tête de
     * conversation n'affichait alors aucun nom du tout.
     */
    private String fullName(UserEntity u) {
        return u.publicDisplayName();
    }
}
