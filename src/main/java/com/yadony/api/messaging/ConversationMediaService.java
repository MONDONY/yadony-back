package com.yadony.api.messaging;

import com.github.benmanes.caffeine.cache.Cache;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Envoi et lecture des photos de messagerie (FLUTTER-B4).
 *
 * <p>Le client n'écrit jamais un message photo : il envoie les octets ici, le back les
 * valide, les ré-encode (EXIF supprimées), les range dans R2, trace les clés en base puis
 * écrit le message dans Firestore. La lecture passe aussi par le back, participant
 * uniquement, sans URL signée : une photo purgée ou un message supprimé répondent 410.
 */
@Service
public class ConversationMediaService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMediaService.class);

    static final int USER_LIMIT = 10;
    static final int CONVERSATION_DAILY_LIMIT = 50;

    /** Même alphabet et même longueur que les identifiants auto de Firestore. */
    private static final String ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 20;
    static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9]{1,40}");

    public enum Variant { THUMB, FULL }

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final ConversationMediaPolicy mediaPolicy;
    private final MessagingImageRepository imageRepository;
    private final StorageService storageService;
    private final FirestoreService firestoreService;
    private final AuditService auditService;
    private final Cache<String, AtomicInteger> userRateCache;
    private final Cache<String, AtomicInteger> conversationRateCache;
    private final String apiBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public ConversationMediaService(ConversationRepository conversationRepository,
                                    ConversationService conversationService,
                                    ConversationMediaPolicy mediaPolicy,
                                    MessagingImageRepository imageRepository,
                                    StorageService storageService,
                                    FirestoreService firestoreService,
                                    AuditService auditService,
                                    @Qualifier("messagingImageUserRateCache")
                                    Cache<String, AtomicInteger> userRateCache,
                                    @Qualifier("messagingImageConversationRateCache")
                                    Cache<String, AtomicInteger> conversationRateCache,
                                    @Value("${app.base-url:http://localhost:8080}") String appBaseUrl) {
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.mediaPolicy = mediaPolicy;
        this.imageRepository = imageRepository;
        this.storageService = storageService;
        this.firestoreService = firestoreService;
        this.auditService = auditService;
        this.userRateCache = userRateCache;
        this.conversationRateCache = conversationRateCache;
        // Même construction que TrackingService : app.base-url s'arrête à l'hôte.
        this.apiBaseUrl = stripTrailingSlash(appBaseUrl) + "/api/v1";
    }

    /**
     * Envoie une photo dans la conversation et rend l'identifiant du message Firestore.
     *
     * @throws ResponseStatusException 403 si l'appelant n'est pas participant
     * @throws YadonyBusinessException 404 si la contrepartie est masquée, 403
     *         {@code media-not-allowed}, 422 (format, taille, {@code invalid-reply-to}),
     *         429 {@code media-rate-limited}
     */
    // Pas de transaction englobante : le traitement de l'image et l'envoi vers R2 prennent
    // du temps, une connexion de base ne doit pas rester tenue pendant ce temps-là.
    public String sendImage(UUID conversationId, UserEntity sender, MultipartFile file, String replyToId) {
        ConversationEntity conv = conversationRepository.findByIdAndParticipant(conversationId, sender.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));
        conversationService.assertMessagingAllowed(conv, sender.getId());
        mediaPolicy.assertAllowed(conv, sender);

        String replyTo = normalizeReplyTo(replyToId);
        storageService.validateImageUpload(file);

        List<AtomicInteger> counters = consumeQuota(sender.getId(), conv.getId());
        String prefix = "messaging/" + conv.getFirestoreConversationId() + "/";
        String messageId = newMessageId();
        StorageService.StoredImage stored = null;
        MessagingImageEntity row = null;
        try {
            byte[] bytes = file.getBytes();
            stored = storageService.storeMessagingImage(prefix, messageId, bytes, file.getContentType());
            row = imageRepository.save(new MessagingImageEntity(conv.getId(), conv.getBidId(), messageId,
                    sender.getId(), stored.mainKey(), stored.thumbnailKey()));
            firestoreService.addImageMessage(conv.getFirestoreConversationId(), messageId,
                    sender.getFirebaseUid(), stored.mainKey(), stored.thumbnailKey(),
                    imageUrl(conv.getId(), messageId), replyTo);
        } catch (IOException | RuntimeException e) {
            counters.forEach(AtomicInteger::decrementAndGet);
            if (row != null) {
                // Message jamais écrit : la ligne ne désigne rien (soft delete, jamais de DELETE).
                row.softDelete();
                imageRepository.save(row);
            }
            if (stored != null) {
                storageService.deleteQuietly(stored.mainKey());
                storageService.deleteQuietly(stored.thumbnailKey());
            }
            if (e instanceof YadonyBusinessException business) {
                throw business;
            }
            log.warn("Envoi de photo en échec pour {} : {}", conv.getId(), e.toString());
            throw new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "media-send-failed",
                    "Service Unavailable", "La photo n'a pas pu être envoyée, réessayez.");
        }

        conversationService.updateLastMessage(conv.getFirestoreConversationId(), FirestoreService.IMAGE_PREVIEW);
        auditService.log("conversation", conv.getId(), "MESSAGE_IMAGE_SENT", sender.getId(),
                Map.of("messageId", messageId));
        return messageId;
    }

    /**
     * Octets d'une photo (variante miniature ou pleine), participant uniquement.
     *
     * @throws YadonyBusinessException 404 {@code image-not-found}, 410 {@code image-unavailable}
     */
    @Transactional(readOnly = true)
    public byte[] loadImage(UUID conversationId, UUID userId, String messageId, Variant variant) {
        ConversationEntity conv = conversationRepository.findByIdAndParticipantIgnoreDeleted(conversationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));
        conversationService.assertMessagingAllowed(conv, userId);

        if (messageId == null || !MESSAGE_ID.matcher(messageId).matches()) {
            throw imageNotFound();
        }
        MessagingImageEntity image = imageRepository
                .findByConversationIdAndFirestoreMessageId(conv.getId(), messageId)
                .orElseThrow(ConversationMediaService::imageNotFound);
        if (image.isPurged()) {
            throw imageGone();
        }
        // Message supprimé par la modération : la photo ne se lit plus (elle reste en R2,
        // une restauration admin la rend de nouveau lisible).
        boolean deleted = firestoreService.findMessage(conv.getFirestoreConversationId(), messageId)
                .map(FirestoreService.MessageSnapshot::deleted)
                .orElse(false);
        if (deleted) {
            throw imageGone();
        }
        String key = variant == Variant.THUMB ? image.getThumbKey() : image.getImageKey();
        return storageService.downloadBytes(key).orElseThrow(ConversationMediaService::imageGone);
    }

    /** URL stable (participant + jeton requis) de la variante pleine, écrite dans le message. */
    String imageUrl(UUID conversationId, String messageId) {
        return apiBaseUrl + "/conversations/" + conversationId + "/messages/" + messageId + "/image";
    }

    private List<AtomicInteger> consumeQuota(UUID userId, UUID conversationId) {
        List<AtomicInteger> taken = new ArrayList<>(2);
        AtomicInteger user = userRateCache.get(userId.toString(), k -> new AtomicInteger());
        taken.add(user);
        AtomicInteger conversation = conversationRateCache.get(conversationId + ":" + userId,
                k -> new AtomicInteger());
        taken.add(conversation);
        boolean overUser = user.incrementAndGet() > USER_LIMIT;
        boolean overConversation = conversation.incrementAndGet() > CONVERSATION_DAILY_LIMIT;
        if (overUser || overConversation) {
            taken.forEach(AtomicInteger::decrementAndGet);
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "media-rate-limited",
                    "Too Many Requests", overUser
                            ? "Trop de photos envoyées. Réessayez dans quelques minutes."
                            : "Limite quotidienne de photos atteinte pour cette conversation.");
        }
        return taken;
    }

    private static String normalizeReplyTo(String replyToId) {
        if (replyToId == null || replyToId.isBlank()) {
            return null;
        }
        String trimmed = replyToId.trim();
        if (!MESSAGE_ID.matcher(trimmed).matches()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-reply-to",
                    "Invalid Reply", "Identifiant du message cité invalide.");
        }
        return trimmed;
    }

    private String newMessageId() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static YadonyBusinessException imageNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "image-not-found", "Not Found",
                "Photo introuvable.");
    }

    private static YadonyBusinessException imageGone() {
        return new YadonyBusinessException(HttpStatus.GONE, "image-unavailable", "Gone",
                "Cette photo n'est plus disponible.");
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
