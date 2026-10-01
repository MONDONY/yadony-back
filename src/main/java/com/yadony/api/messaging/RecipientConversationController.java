package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.dto.ConversationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code GET /conversations/bid/{bidId}/recipient} : conversation voyageur ↔ destinataire
 * (lot 3C).
 *
 * <p>Contrôleur séparé de {@link ConversationController} pour ne relâcher que cet endpoint :
 * tout utilisateur authentifié (hors invité, refusé par la règle finale de SecurityConfig),
 * comme {@code /receptions}. Un destinataire n'a pas à porter un rôle SENDER ou TRAVELER ;
 * l'autorisation réelle (voyageur du colis ou destinataire du lien CONFIRMED) est vérifiée par
 * {@link RecipientConversationService}.
 */
@RestController
@RequestMapping("/conversations")
@PreAuthorize("isAuthenticated()")
public class RecipientConversationController {

    private final RecipientConversationService recipientConversationService;
    private final ConversationService conversationService;
    private final UserRepository userRepository;

    public RecipientConversationController(RecipientConversationService recipientConversationService,
                                           ConversationService conversationService,
                                           UserRepository userRepository) {
        this.recipientConversationService = recipientConversationService;
        this.conversationService = conversationService;
        this.userRepository = userRepository;
    }

    @GetMapping("/bid/{bidId}/recipient")
    public ResponseEntity<ConversationResponse> getRecipientConversation(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID bidId) {
        UserEntity caller = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "user-not-found",
                        "Unauthorized", "Utilisateur introuvable"));
        ConversationEntity conv = recipientConversationService.getOrCreate(bidId, caller.getId());
        return ResponseEntity.ok(conversationService.toResponse(conv, caller.getId()));
    }
}
