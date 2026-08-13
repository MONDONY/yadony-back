package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.PageResponse;
import com.yadony.api.common.StorageService;
import java.util.List;
import com.yadony.api.messaging.dto.ConversationResponse;
import com.yadony.api.messaging.dto.ImageUploadResponse;
import com.yadony.api.messaging.dto.LastMessageRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
public class ConversationController {

    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5 MB

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final UserRepository userRepository;
    private final StorageService storageService;

    public ConversationController(ConversationRepository conversationRepository,
                                   ConversationService conversationService,
                                   UserRepository userRepository,
                                   StorageService storageService) {
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    // GET /conversations — paginated list for the authenticated user
    @GetMapping
    public ResponseEntity<PageResponse<ConversationResponse>> listConversations(
            @PageableDefault(size = 20) Pageable pageable) {

        UserEntity currentUser = resolveCurrentUser();
        Page<ConversationEntity> page = conversationRepository
                .findByParticipant(currentUser.getId(), pageable);

        java.util.Map<String, java.util.Map<String, Object>> meta = conversationService.fetchConversationMeta(
                page.getContent().stream().map(ConversationEntity::getFirestoreConversationId).toList());

        Page<ConversationResponse> responsePage = page.map(
                c -> conversationService.toResponse(c, currentUser.getId(), meta));
        return ResponseEntity.ok(PageResponse.from(responsePage));
    }

    // GET /conversations/{id} — single conversation
    @GetMapping("/{id}")
    public ResponseEntity<ConversationResponse> getConversation(
            @PathVariable UUID id) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationRepository
                .findByIdAndParticipant(id, currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));

        return ResponseEntity.ok(conversationService.toResponse(conv, currentUser.getId()));
    }

    // GET /conversations/bid/{bidId} — conversation liée à un bid (get or create)
    @GetMapping("/bid/{bidId}")
    public ResponseEntity<ConversationResponse> getConversationByBidId(
            @PathVariable UUID bidId) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationService.getOrCreateByBidId(bidId, currentUser.getId());
        return ResponseEntity.ok(conversationService.toResponse(conv, currentUser.getId()));
    }

    // POST /conversations/{id}/last-message — update Firestore last message preview
    @PostMapping("/{id}/last-message")
    public ResponseEntity<Void> updateLastMessage(
            @PathVariable UUID id,
            @Valid @RequestBody LastMessageRequest body) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationRepository
                .findByIdAndParticipant(id, currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));

        conversationService.updateLastMessage(conv.getFirestoreConversationId(), body.preview());

        return ResponseEntity.noContent().build();
    }

    // DELETE /conversations/{id} — unilateral soft-delete (other party goes read-only)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable UUID id) {
        UserEntity currentUser = resolveCurrentUser();
        conversationService.deleteConversation(id, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    // GET /conversations/archived — conversations archivées par l'utilisateur courant
    @GetMapping("/archived")
    public ResponseEntity<PageResponse<ConversationResponse>> listArchivedConversations(
            @PageableDefault(size = 50, sort = "updatedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        UserEntity currentUser = resolveCurrentUser();
        Pageable bounded = PageRequest.of(
                pageable.getPageNumber(), Math.min(pageable.getPageSize(), 50), pageable.getSort());
        return ResponseEntity.ok(PageResponse.from(
                conversationService.getArchivedConversations(currentUser.getId(), bounded)));
    }

    // POST /conversations/{id}/archive — archiver une conversation
    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archiveConversation(@PathVariable UUID id) {
        UserEntity currentUser = resolveCurrentUser();
        conversationService.archiveConversation(id, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    // POST /conversations/{id}/unarchive — désarchiver une conversation
    @PostMapping("/{id}/unarchive")
    public ResponseEntity<Void> unarchiveConversation(@PathVariable UUID id) {
        UserEntity currentUser = resolveCurrentUser();
        conversationService.unarchiveConversation(id, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    // POST /conversations/{id}/restore — restore the requesting user's deleted copy
    @PostMapping("/{id}/restore")
    public ResponseEntity<ConversationResponse> restoreConversation(@PathVariable UUID id) {
        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationService.restoreConversation(id, currentUser.getId());
        return ResponseEntity.ok(conversationService.toResponse(conv, currentUser.getId()));
    }

    // POST /conversations/{id}/upload — upload image to S3
    @PostMapping("/{id}/upload")
    public ResponseEntity<ImageUploadResponse> uploadImage(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {

        UserEntity currentUser = resolveCurrentUser();
        ConversationEntity conv = conversationRepository
                .findByIdAndParticipant(id, currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));

        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "File exceeds 5MB limit");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only image files are allowed");
        }

        String prefix = "messaging/" + conv.getFirestoreConversationId() + "/";
        String key;
        try {
            key = storageService.uploadFile(file, prefix);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload file");
        }

        String presignedUrl = storageService.generatePresignedUrl(key, Duration.ofDays(7));
        return ResponseEntity.ok(new ImageUploadResponse(presignedUrl, key));
    }

    private UserEntity resolveCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String uid = (String) auth.getPrincipal();
        return userRepository.findByFirebaseUid(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "User not found for uid: " + uid));
    }
}
