package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.dto.NotifyMessageRequest;
import com.yadony.api.messaging.dto.NotifyMessageResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@RestController
@RequestMapping("/internal/messaging")
public class MessagingNotifyController {

    @Value("${yadony.internal.secret:}")
    private String internalSecret;

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final NotificationDispatcher notificationDispatcher;
    private final UserRepository userRepository;

    public MessagingNotifyController(ConversationRepository conversationRepository,
                                      ConversationService conversationService,
                                      NotificationDispatcher notificationDispatcher,
                                      UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.notificationDispatcher = notificationDispatcher;
        this.userRepository = userRepository;
    }

    @PostMapping("/notify")
    public ResponseEntity<NotifyMessageResponse> notify(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret,
            @Valid @RequestBody NotifyMessageRequest request) {

        if (internalSecret == null || internalSecret.isBlank()
                || secret == null
                || !MessageDigest.isEqual(
                        internalSecret.getBytes(StandardCharsets.UTF_8),
                        secret.getBytes(StandardCharsets.UTF_8))) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "invalid-internal-secret", "Unauthorized", "Invalid internal secret");
        }

        var conv = conversationRepository
                .findByFirestoreConversationId(request.conversationId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "conversation-not-found", "Not Found", "Conversation introuvable"));

        // Répare au passage le document Firestore s'il manque. Les conversations
        // créées pendant que le bean Firestore était nul n'ont que leur
        // sous-collection `messages` : la Cloud Function ne peut alors pas y lire
        // les participants, et l'aperçu du dernier message reste figé côté liste.
        conversationService.ensureFirestoreDocument(conv);

        // Défense en profondeur : le blocage réel de l'écriture d'un message par un
        // expéditeur muté est la règle de sécurité Firestore (moderation/{firebaseUid}),
        // les clients écrivant directement dans Firestore sans passer par ce backend.
        // Ce filet évite seulement de notifier le destinataire si, malgré tout, un
        // message d'un expéditeur muté est arrivé jusqu'ici.
        UserEntity sender = userRepository.findByFirebaseUid(request.senderFirebaseUid()).orElse(null);
        if (sender != null && sender.isMessagingMuted(Instant.now())) {
            return ResponseEntity.ok(new NotifyMessageResponse(null));
        }

        String preview = request.messagePreview() != null ? request.messagePreview() : "[Image]";
        String recipientFirebaseUid = notificationDispatcher.sendMessageNotification(
                conv.getSenderId(), conv.getTravelerId(),
                request.senderFirebaseUid(), preview, conv.getFirestoreConversationId());

        return ResponseEntity.ok(new NotifyMessageResponse(recipientFirebaseUid));
    }
}
