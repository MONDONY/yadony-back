package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.CallAvailability;
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
    private final BlockVisibility blockVisibility;
    private final CallAvailability callAvailability;
    private final ConversationMediaPolicy mediaPolicy;
    private final MessagingImageRetentionService imageRetention;

    /** Statuts où un appel peut être permis ; la fenêtre exacte (J+3 après livraison) reste à calls/. */
    private static final java.util.Set<BidStatus> CALL_CANDIDATE_STATUSES = java.util.EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED);

    public ConversationService(ConversationRepository conversationRepository,
                                FirestoreService firestoreService,
                                UserRepository userRepository,
                                AuditService auditService,
                                BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                StorageService storageService,
                                BlockVisibility blockVisibility,
                                CallAvailability callAvailability,
                                ConversationMediaPolicy mediaPolicy,
                                MessagingImageRetentionService imageRetention) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.storageService = storageService;
        this.blockVisibility = blockVisibility;
        this.callAvailability = callAvailability;
        this.mediaPolicy = mediaPolicy;
        this.imageRetention = imageRetention;
    }

    @Transactional
    public ConversationEntity getOrCreateByBidId(UUID bidId, UUID requestingUserId) {
        // Return the conversation if it's still visible to the requesting user
        Optional<ConversationEntity> accessible =
            conversationRepository.findByBidIdAndParticipant(bidId, requestingUserId);
        if (accessible.isPresent()) {
            // Un fil dont la contrepartie est masquée n'existe plus pour l'appelant : 404
            // silencieux. La transaction en cours est déjà l'exception portée par
            // BlockVisibility, si bien qu'un colis en cours d'acheminement passe toujours.
            blockVisibility.assertVisible(requestingUserId, otherUserId(accessible.get(), requestingUserId));
            return accessible.get();
        }

        // The requesting user deleted their copy — return it anyway with deletedBySelf flag
        // so the caller can offer a "Restore" option instead of throwing GONE.
        Optional<ConversationEntity> deletedBySelf =
            conversationRepository.findByBidIdAndParticipantIgnoreDeleted(bidId, requestingUserId);
        if (deletedBySelf.isPresent()) {
            blockVisibility.assertVisible(requestingUserId, otherUserId(deletedBySelf.get(), requestingUserId));
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

        // Aucune conversation ne s'ouvre entre deux comptes masqués l'un pour l'autre.
        blockVisibility.assertVisible(requestingUserId,
            requestingUserId.equals(senderId) ? travelerId : senderId);

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
        // Le masquage étant symétrique, le sens de la vérification n'a pas d'importance :
        // si l'un des deux a bloqué l'autre et qu'aucune transaction ne les lie, aucun fil
        // ne doit naître. Sur le chemin « bid accepté », la transaction est active, donc
        // isHidden rend faux et la conversation se crée normalement.
        blockVisibility.assertVisible(senderId, travelerId);

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

            UserEntity sender   = userRepository.findById(conv.participantAId()).orElse(null);
            UserEntity traveler = userRepository.findById(conv.getTravelerId()).orElse(null);
            if (sender == null || traveler == null) {
                log.warn("Firestore document repair skipped for {} — participant missing", firestoreId);
                return;
            }

            Map<String, Object> data = new java.util.HashMap<>();
            // Conversation destinataire fermée : le destinataire révoqué ne doit pas
            // retrouver son accès Firestore par une réparation (cf. revoke).
            if (conv.isRecipientConversation() && conv.isClosed()) {
                data.put("senderId", null);
                data.put("revokedRecipientId", sender.getFirebaseUid());
            } else {
                data.put("senderId", sender.getFirebaseUid());
            }
            data.put("travelerId", traveler.getFirebaseUid());
            if (conv.isRecipientConversation()) {
                data.put("kind", ConversationKind.RECIPIENT_TRAVELER.name());
            }
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
            // Photos (FLUTTER-B4) : purge immédiate des objets R2, sauf litige ou
            // signalement ouvert — elles suivent alors l'échéance normale.
            try {
                imageRetention.purgeConversation(conv, requestingUserId);
            } catch (Exception e) {
                log.warn("Purge des photos en échec pour {}: {}", conversationId, e.getMessage());
            }
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

    /**
     * Sourdine (FLUTTER-CM) : coupe les push des nouveaux messages de ce fil pour l'appelant
     * seul. Idempotent : un second appel ne réécrit rien et ne journalise rien. Participant
     * uniquement, archivé compris (on peut mettre en sourdine un fil archivé), sinon 403.
     */
    @Transactional
    public void muteNotifications(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreArchived(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));
        if (conv.isNotificationsMutedBy(requestingUserId)) {
            return;
        }
        conv.muteNotificationsForUser(requestingUserId);
        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_NOTIFICATIONS_MUTED", requestingUserId, Map.of());
    }

    /** Réactive les push de ce fil pour l'appelant. Idempotent, participant uniquement. */
    @Transactional
    public void unmuteNotifications(UUID conversationId, UUID requestingUserId) {
        ConversationEntity conv = conversationRepository
            .findByIdAndParticipantIgnoreArchived(conversationId, requestingUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conversation not found or access denied"));
        if (!conv.isNotificationsMutedBy(requestingUserId)) {
            return;
        }
        conv.unmuteNotificationsForUser(requestingUserId);
        conversationRepository.save(conv);
        auditService.log("conversation", conversationId, "CONVERSATION_NOTIFICATIONS_UNMUTED", requestingUserId, Map.of());
    }

    /**
     * Garde d'envoi d'un message : lève un 404 si la contrepartie du fil est masquée
     * pour {@code actorId}.
     *
     * <p>Posée ici et non seulement dans le contrôleur, pour que tout chemin qui poste
     * dans une conversation (aperçu du dernier message, envoi d'image) passe par la même
     * règle. Elle reste ouverte tant qu'une transaction lie les deux comptes : un colis
     * en cours d'acheminement continue de se coordonner malgré le blocage.
     */
    @Transactional(readOnly = true)
    public void assertMessagingAllowed(ConversationEntity conv, UUID actorId) {
        blockVisibility.assertVisible(actorId, otherUserId(conv, actorId));
    }

    public List<ConversationResponse> getArchivedConversations(UUID userId) {
        // Liste non paginée : le filtrage en mémoire suffit et évite une requête dédiée.
        // La liste active, elle, est paginée et doit filtrer en base (cf.
        // ConversationRepository#findByParticipantExcludingHidden) sous peine de fausser
        // le nombre total de pages.
        java.util.Set<UUID> hidden = blockVisibility.hiddenUserIdsFor(userId);
        List<ConversationEntity> archived = conversationRepository
            .findArchivedByParticipant(userId, Pageable.unpaged())
            .getContent()
            .stream()
            .filter(c -> !hidden.contains(otherUserId(c, userId)))
            .toList();
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

    /** Convenience : fait son propre aller-retour Firestore pour une conversation seule. */
    public ConversationResponse toResponse(ConversationEntity conv, UUID currentUserId) {
        Map<String, Object> meta = firestoreService
            .getConversationMeta(List.of(conv.getFirestoreConversationId()))
            .get(conv.getFirestoreConversationId());
        return buildResponse(conv, currentUserId, meta, false);
    }

    /** Variante batch : réutilise une map déjà chargée (cf. {@link #fetchConversationMeta}). */
    public ConversationResponse toResponse(ConversationEntity conv, UUID currentUserId,
                                            Map<String, Map<String, Object>> metaByFirestoreId) {
        Map<String, Object> meta = metaByFirestoreId.get(conv.getFirestoreConversationId());
        return buildResponse(conv, currentUserId, meta, false);
    }

    /**
     * Réponses d'une page de la liste des conversations, en une seule transaction.
     *
     * <p>Chaque réponse lit l'interlocuteur, le colis et le trajet, et les fils d'une
     * commande en cours relisent aussi la conversation, le colis et l'utilisateur pour le
     * bouton d'appel et les photos. Fil par fil et hors transaction, une page de 20
     * coûtait de 60 à plus de 200 requêtes, chacune empruntant sa propre connexion : sous
     * charge, ces emprunts faisaient la queue et la liste montait à 15 s (test k6 du
     * 08/10/2026, 200 utilisateurs). Les entités de la page sont donc chargées par lot
     * d'abord : les {@code findById} qui suivent, ici comme dans
     * {@link com.yadony.api.calls.CallEligibilityService} et {@link ConversationMediaPolicy},
     * les trouvent dans le contexte de persistance sans requête SQL.
     *
     * <p>Firestore est lu avant, par l'appelant : aucune connexion n'est tenue pendant cet
     * appel réseau.
     *
     * <p>Contrat : {@code page} ne contient que des fils dont la contrepartie est visible
     * (liste déjà filtrée par {@code hiddenUserIdsFor}). Le blocage étant symétrique, les
     * contrôles d'appel et de photos ne le relisent pas fil par fil.
     */
    @Transactional(readOnly = true)
    public List<ConversationResponse> toResponses(List<ConversationEntity> page, UUID currentUserId,
                                                  Map<String, Map<String, Object>> metaByFirestoreId) {
        prefetch(page, currentUserId);
        return page.stream()
            .map(c -> buildResponse(c, currentUserId, metaByFirestoreId.get(c.getFirestoreConversationId()), true))
            .toList();
    }

    private void prefetch(List<ConversationEntity> page, UUID currentUserId) {
        if (page.isEmpty()) {
            return;
        }
        conversationRepository.findAllById(page.stream().map(ConversationEntity::getId).toList());
        java.util.Set<UUID> userIds = new java.util.HashSet<>();
        userIds.add(currentUserId);
        page.forEach(c -> userIds.add(otherUserId(c, currentUserId)));
        userRepository.findAllById(userIds);
        List<UUID> bidIds = page.stream().map(ConversationEntity::getBidId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (bidIds.isEmpty()) {
            return;
        }
        List<UUID> announcementIds = bidRepository.findAllById(bidIds).stream()
            .map(BidEntity::getAnnouncementId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!announcementIds.isEmpty()) {
            announcementRepository.findAllById(announcementIds);
        }
    }

    /** Interlocuteur de la conversation, déduit de l'entité sans requête. */
    private static UUID otherUserId(ConversationEntity conv, UUID currentUserId) {
        return conv.participantAId().equals(currentUserId) ? conv.getTravelerId() : conv.participantAId();
    }

    private ConversationResponse buildResponse(ConversationEntity conv, UUID currentUserId,
                                                Map<String, Object> meta, boolean visibilityChecked) {
        UUID otherUserId = otherUserId(conv, currentUserId);

        UserEntity other = userRepository.findById(otherUserId).orElse(null);
        // Participant A = expéditeur ou destinataire selon le type (cf. ConversationEntity).
        String role = otherUserId.equals(conv.getTravelerId()) ? "Voyageur"
                : conv.isRecipientConversation() ? "Destinataire" : "Expéditeur";

        String tripOrigin      = null;
        String tripDestination = null;
        String tripDate        = null;
        Double tripWeightKg    = null;
        String bidStatus       = null;
        boolean revealPhone    = false;

        Optional<BidEntity> bidOpt = conv.getBidId() == null ? Optional.empty() : bidRepository.findById(conv.getBidId());
        if (bidOpt.isPresent()) {
            BidEntity bid = bidOpt.get();
            tripWeightKg = bid.getWeightKg() != null ? bid.getWeightKg().doubleValue() : null;
            bidStatus    = mapBidStatus(bid.getStatus());
            // Téléphone révélé seulement quand le deal est actif (même règle que
            // BidService), et jamais si l'intéressé a masqué son numéro dans ses
            // réglages de confidentialité — le chat reste alors son seul canal.
            // Conversation destinataire : jamais de téléphone (le contact passe par le chat
            // et par les canaux du lot 3B, qui ont leurs propres règles).
            // Retour d'un colis annulé après remise : le numéro reste communicable (FLUTTER-FM).
            revealPhone  = !conv.isRecipientConversation()
                    && com.yadony.api.matching.ContactWindow.phoneVisible(bid,
                            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))
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
            conv.isDeletedByUser(currentUserId),
            conv.getKind() != null ? conv.getKind().name() : ConversationKind.SENDER_TRAVELER.name(),
            !conv.isRecipientConversation() ? null
                    : currentUserId.equals(conv.getTravelerId()) ? "TRAVELER" : "RECIPIENT",
            callAvailable(conv, currentUserId, bidOpt.orElse(null), visibilityChecked),
            conv.isNotificationsMutedBy(currentUserId),
            mediaAllowed(conv, currentUserId, bidOpt.orElse(null), visibilityChecked)
        );
    }

    /**
     * Photos permises maintenant (FLUTTER-B4, {@link ConversationMediaPolicy}). Comme
     * l'appel : un échec de calcul masque la fonction, il ne casse jamais l'affichage.
     */
    private boolean mediaAllowed(ConversationEntity conv, UUID currentUserId, BidEntity bid,
                                 boolean visibilityChecked) {
        if (!isContactCandidate(conv, bid)) {
            return false;
        }
        try {
            return mediaPolicy.check(conv, currentUserId, bid, null, visibilityChecked).isEmpty();
        } catch (Exception e) {
            log.warn("mediaAllowed indisponible pour {} : {}", conv.getId(), e.toString());
            return false;
        }
    }

    /** Le bouton d'appel ne doit jamais casser l'affichage d'une conversation : en cas d'échec, pas de bouton. */
    private boolean callAvailable(ConversationEntity conv, UUID currentUserId, BidEntity bid,
                                  boolean visibilityChecked) {
        // Filtre sans requête : la règle complète (plusieurs lectures) ne tourne que pour une commande
        // en cours dans une conversation active, soit une poignée de fils par liste.
        if (!isContactCandidate(conv, bid) || conv.isClosed()
                || conv.isDeletedByUser(currentUserId) || conv.isArchivedByUser(currentUserId)) {
            return false;
        }
        try {
            return visibilityChecked
                    ? callAvailability.canCall(currentUserId, conv.getId(), true)
                    : callAvailability.canCall(currentUserId, conv.getId());
        } catch (Exception e) {
            log.warn("callAvailable indisponible pour {} : {}", conv.getId(), e.toString());
            return false;
        }
    }

    /**
     * Pré-filtre sans requête de l'appel et des photos : commande en cours, ou retour d'un colis
     * annulé après sa remise (FLUTTER-FM, expéditeur et voyageur seulement). La règle complète
     * reste à {@code calls/} et à {@link ConversationMediaPolicy}.
     */
    private static boolean isContactCandidate(ConversationEntity conv, BidEntity bid) {
        if (bid == null || bid.getStatus() == null) return false;
        if (CALL_CANDIDATE_STATUSES.contains(bid.getStatus())) return true;
        return !conv.isRecipientConversation()
                && com.yadony.api.matching.ContactWindow.isReturnInProgress(bid,
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    /**
     * Trie les conversations par dernier message, la plus récente en tête.
     *
     * <p>Même instant que celui affiché par la liste ({@code lastMessageAt} Firestore,
     * repli sur {@code updated_at}) ; à égalité, l'identifiant départage pour que deux
     * appels successifs rendent le même ordre et que la pagination reste stable.
     */
    static List<ConversationEntity> sortByLastActivity(List<ConversationEntity> conversations,
                                                       Map<String, Map<String, Object>> metaByFirestoreId) {
        java.util.Comparator<ConversationEntity> byActivity = java.util.Comparator.comparing(
                (ConversationEntity c) -> parseLastMessageAt(
                        metaByFirestoreId.get(c.getFirestoreConversationId()), c.getUpdatedAt()),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
        return conversations.stream()
                .sorted(byActivity.thenComparing(ConversationEntity::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
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
            // Colis remis puis en route : le fil reste « En cours » côté app. Sans
            // valeur, il sortait de ce filtre dès la remise, au moment où l'on se
            // parle le plus. Un client plus ancien ignore ce code (bandeau masqué).
            case HANDED_OVER, IN_TRANSIT -> "IN_TRANSIT";
            case ARRIVED -> "TRIP_ARRIVED";
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
