package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.AddNoteRequest;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.ConversationContextResponse;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.NoteResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Outils de la modération d'une conversation, adressée par son identifiant Firestore (comme le reste de
 * {@code /admin/conversations}) : contexte (historique, notes, signalements, participants) et notes
 * internes. La lecture demande MODERATION_VIEW ; écrire une note demande de pouvoir agir sur les
 * messages (MESSAGE_DELETE), comme la suppression et la restauration.
 */
@RestController
@RequestMapping("/admin/conversations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminConversationToolsController {

    private final ConversationRepository conversations;
    private final AdminConversationToolsService tools;

    public AdminConversationToolsController(ConversationRepository conversations, AdminConversationToolsService tools) {
        this.conversations = conversations;
        this.tools = tools;
    }

    @PreAuthorize("hasAuthority('MODERATION_VIEW')")
    @GetMapping("/{conversationId}/context")
    public ResponseEntity<ConversationContextResponse> context(@PathVariable String conversationId) {
        return ResponseEntity.ok(tools.context(require(conversationId)));
    }

    @PreAuthorize("hasAuthority('MESSAGE_DELETE')")
    @PostMapping("/{conversationId}/notes")
    public ResponseEntity<NoteResponse> addNote(@PathVariable String conversationId,
                                                @Valid @RequestBody AddNoteRequest request,
                                                Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        var saved = tools.addNote(require(conversationId), adminId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new NoteResponse(saved.getId(), ((AdminPrincipal) authentication.getPrincipal()).email(), saved.getContent(),
                        saved.getCreatedAt()));
    }

    private ConversationEntity require(String firestoreId) {
        return conversations.findByFirestoreConversationId(firestoreId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "conversation-not-found",
                        "Not Found", "Conversation introuvable"));
    }
}
