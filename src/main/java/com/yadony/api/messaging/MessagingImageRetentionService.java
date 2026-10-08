package com.yadony.api.messaging;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Conservation des photos de messagerie (FLUTTER-B4).
 *
 * <ul>
 *   <li>bid livré (COMPLETED) : purge 7 jours après la livraison ({@code delivered_at},
 *       repli sur la dernière mise à jour du bid) ;</li>
 *   <li>bid terminé autrement (annulé, no-show, refusé, expiré…) : 7 jours après la fin,
 *       approchée par la dernière mise à jour du bid, qui porte le changement de statut ;</li>
 *   <li>bid encore en cours : rien ;</li>
 *   <li>exception : rien n'est purgé tant qu'un litige ou un signalement est ouvert
 *       ({@link MessagingMediaRetentionHold}) ;</li>
 *   <li>les deux parties suppriment la conversation : purge immédiate de ses objets, sous
 *       la même exception.</li>
 * </ul>
 * Après purge, la ligne reste avec {@code purged_at} et le message Firestore reçoit
 * {@code imageExpired: true} : l'app affiche « Photo expirée ».
 */
@Service
public class MessagingImageRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MessagingImageRetentionService.class);

    static final int RETENTION_DAYS = 7;

    /** Bids encore vivants : leurs photos ne sont jamais purgées par l'échéance. */
    static final Set<BidStatus> RUNNING_STATUSES = EnumSet.of(
            BidStatus.AWAITING_PAYMENT, BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING,
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

    private final MessagingImageRepository imageRepository;
    private final ConversationRepository conversationRepository;
    private final BidRepository bidRepository;
    private final StorageService storageService;
    private final FirestoreService firestoreService;
    private final AuditService auditService;
    private final List<MessagingMediaRetentionHold> holds;
    private final Clock clock;

    @Autowired
    public MessagingImageRetentionService(MessagingImageRepository imageRepository,
                                          ConversationRepository conversationRepository,
                                          BidRepository bidRepository,
                                          StorageService storageService,
                                          FirestoreService firestoreService,
                                          AuditService auditService,
                                          List<MessagingMediaRetentionHold> holds) {
        this(imageRepository, conversationRepository, bidRepository, storageService, firestoreService,
                auditService, holds, Clock.systemUTC());
    }

    MessagingImageRetentionService(MessagingImageRepository imageRepository,
                                   ConversationRepository conversationRepository,
                                   BidRepository bidRepository,
                                   StorageService storageService,
                                   FirestoreService firestoreService,
                                   AuditService auditService,
                                   List<MessagingMediaRetentionHold> holds,
                                   Clock clock) {
        this.imageRepository = imageRepository;
        this.conversationRepository = conversationRepository;
        this.bidRepository = bidRepository;
        this.storageService = storageService;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
        this.holds = holds;
        this.clock = clock;
    }

    /**
     * Fin de conservation des photos d'un bid ; vide tant que le bid est en cours.
     */
    static Optional<LocalDateTime> retentionEnd(BidEntity bid) {
        BidStatus status = bid.getStatus();
        if (status == null || RUNNING_STATUSES.contains(status)) {
            return Optional.empty();
        }
        LocalDateTime base = status == BidStatus.COMPLETED && bid.getDeliveredAt() != null
                ? bid.getDeliveredAt() : bid.getUpdatedAt();
        return base == null ? Optional.empty() : Optional.of(base.plusDays(RETENTION_DAYS));
    }

    /** Passe planifiée : purge les photos de chaque bid échu. Rend le nombre de photos purgées. */
    public int purgeExpired() {
        int purged = 0;
        for (UUID bidId : imageRepository.findBidIdsWithLiveImages()) {
            try {
                purged += purgeBidIfDue(bidId);
            } catch (Exception e) {
                log.warn("MessagingImageRetention: purge du bid {} en échec : {}", bidId, e.toString());
            }
        }
        if (purged > 0) {
            log.info("MessagingImageRetention: {} photo(s) purgée(s)", purged);
        }
        return purged;
    }

    int purgeBidIfDue(UUID bidId) {
        LocalDateTime now = now();
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        // Bid disparu : plus rien ne justifie de garder ses photos.
        if (bid != null) {
            Optional<LocalDateTime> end = retentionEnd(bid);
            if (end.isEmpty() || now.isBefore(end.get())) {
                return 0;
            }
        }
        List<MessagingImageEntity> images = imageRepository.findLiveByBidId(bidId);
        if (images.isEmpty()) {
            return 0;
        }
        Set<UUID> conversationIds = new LinkedHashSet<>();
        images.forEach(i -> conversationIds.add(i.getConversationId()));
        conversationRepository.findAllByBidId(bidId).forEach(c -> conversationIds.add(c.getId()));
        if (isHeld(bidId, conversationIds)) {
            return 0;
        }

        Map<UUID, ConversationEntity> conversations = conversationRepository.findAllById(conversationIds).stream()
                .collect(Collectors.toMap(ConversationEntity::getId, Function.identity(), (a, b) -> a));
        int purged = 0;
        for (MessagingImageEntity image : images) {
            if (purgeImage(image, conversations.get(image.getConversationId()), now)) {
                purged++;
            }
        }
        if (purged > 0) {
            auditService.log("bid", bidId, "MESSAGING_IMAGES_PURGED", null,
                    Map.of("count", purged, "reason", "RETENTION_EXPIRED"));
        }
        return purged;
    }

    /**
     * Purge immédiate : les deux parties ont supprimé la conversation. Rend faux si une
     * procédure ouverte retient les photos (elles suivront alors l'échéance normale).
     */
    @Transactional
    public boolean purgeConversation(ConversationEntity conv, UUID actorId) {
        List<UUID> conversationIds = new ArrayList<>();
        conversationIds.add(conv.getId());
        if (conv.getBidId() != null) {
            conversationRepository.findAllByBidId(conv.getBidId()).stream()
                    .map(ConversationEntity::getId)
                    .filter(id -> !id.equals(conv.getId()))
                    .forEach(conversationIds::add);
        }
        if (isHeld(conv.getBidId(), conversationIds)) {
            log.info("MessagingImageRetention: photos de {} retenues (procédure ouverte)", conv.getId());
            return false;
        }

        // Le préfixe couvre aussi les pièces de l'ancien POST /upload, jamais tracées en base.
        try {
            storageService.deleteByPrefix("messaging/" + conv.getFirestoreConversationId() + "/");
        } catch (Exception e) {
            log.warn("MessagingImageRetention: deleteByPrefix en échec pour {} : {}", conv.getId(), e.toString());
            return false;
        }
        LocalDateTime now = now();
        int count = 0;
        for (MessagingImageEntity image : imageRepository.findByConversationId(conv.getId())) {
            if (!image.isPurged()) {
                image.markPurged(now);
                imageRepository.save(image);
                count++;
            }
        }
        if (count > 0) {
            auditService.log("conversation", conv.getId(), "MESSAGING_IMAGES_PURGED", actorId,
                    Map.of("count", count, "reason", "CONVERSATION_PURGED"));
        }
        return true;
    }

    private boolean purgeImage(MessagingImageEntity image, ConversationEntity conv, LocalDateTime now) {
        try {
            storageService.deleteFile(image.getImageKey());
            storageService.deleteFile(image.getThumbKey());
        } catch (Exception e) {
            // Ligne laissée intacte : la prochaine passe réessaiera.
            log.warn("MessagingImageRetention: suppression R2 en échec pour {} : {}", image.getId(), e.toString());
            return false;
        }
        image.markPurged(now);
        imageRepository.save(image);
        if (conv != null) {
            firestoreService.markImageExpired(conv.getFirestoreConversationId(), image.getFirestoreMessageId());
        }
        return true;
    }

    private boolean isHeld(UUID bidId, Collection<UUID> conversationIds) {
        for (MessagingMediaRetentionHold hold : holds) {
            if (hold.holds(bidId, conversationIds)) {
                return true;
            }
        }
        return false;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}
