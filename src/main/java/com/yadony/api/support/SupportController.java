package com.yadony.api.support;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.support.dto.CreateSupportMessageRequest;
import com.yadony.api.support.dto.CreateSupportTicketRequest;
import com.yadony.api.support.dto.SupportAttachmentResponse;
import com.yadony.api.support.dto.SupportMessageResponse;
import com.yadony.api.support.dto.SupportPredefinedReplyResponse;
import com.yadony.api.support.dto.SupportTicketResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API support cote utilisateur. Volontairement en REST pur : contrairement a la
 * messagerie P2P (Firestore), le support n'a pas besoin de temps reel — l'app
 * recharge a l'ouverture de l'ecran et apres envoi.
 */
@RestController
@RequestMapping("/support")
public class SupportController {

    private static final int MAX_PAGE_SIZE = 50;

    private final SupportTicketService supportTicketService;
    private final SupportAttachmentService attachmentService;
    private final StorageService storageService;
    private final UserRepository userRepository;

    public SupportController(SupportTicketService supportTicketService,
                             SupportAttachmentService attachmentService,
                             StorageService storageService,
                             UserRepository userRepository) {
        this.supportTicketService = supportTicketService;
        this.attachmentService = attachmentService;
        this.storageService = storageService;
        this.userRepository = userRepository;
    }

    @GetMapping("/replies")
    public List<SupportPredefinedReplyResponse> listReplies() {
        return supportTicketService.listActiveReplies().stream()
                .map(SupportPredefinedReplyResponse::from)
                .toList();
    }

    @GetMapping("/tickets")
    public Page<SupportTicketResponse> listTickets(@AuthenticationPrincipal String firebaseUid,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        UserEntity user = requireUser(firebaseUid);
        return supportTicketService
                .listUserTickets(user.getId(), PageRequest.of(Math.max(page, 0), clampSize(size)))
                .map(t -> SupportTicketResponse.summary(t, supportTicketService.unreadCount(t)));
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicketResponse createTicket(@AuthenticationPrincipal String firebaseUid,
                                              @Valid @RequestBody CreateSupportTicketRequest request) {
        UserEntity user = requireUser(firebaseUid);
        SupportTicketEntity ticket = supportTicketService.createTicket(
                user, request.category(), request.subject(), request.message(),
                request.attachmentKeys());
        List<SupportMessageEntity> messages = supportTicketService.listMessages(ticket.getId());
        List<UUID> messageIds = messages.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<SupportAttachmentResponse>> attachMap = attachmentService.responsesFor(messageIds);
        List<SupportMessageResponse> mapped = messages.stream()
                .map(m -> SupportMessageResponse.from(m, attachMap.getOrDefault(m.getId(), List.of())))
                .toList();
        // unreadCount = 0 : le seul message est celui de l'utilisateur, pas un message admin
        return SupportTicketResponse.withMessages(ticket, mapped, 0L);
    }

    @GetMapping("/tickets/{ticketId}")
    public SupportTicketResponse getTicket(@AuthenticationPrincipal String firebaseUid,
                                           @PathVariable UUID ticketId) {
        UserEntity user = requireUser(firebaseUid);
        SupportTicketEntity ticket = supportTicketService.getUserTicket(user, ticketId);
        List<SupportMessageEntity> messages = supportTicketService.listMessages(ticketId);
        List<UUID> messageIds = messages.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<SupportAttachmentResponse>> attachMap = attachmentService.responsesFor(messageIds);
        List<SupportMessageResponse> mapped = messages.stream()
                .map(m -> SupportMessageResponse.from(m, attachMap.getOrDefault(m.getId(), List.of())))
                .toList();
        return SupportTicketResponse.withMessages(ticket, mapped, supportTicketService.unreadCount(ticket));
    }

    @PostMapping("/tickets/{ticketId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageResponse addMessage(@AuthenticationPrincipal String firebaseUid,
                                             @PathVariable UUID ticketId,
                                             @Valid @RequestBody CreateSupportMessageRequest request) {
        UserEntity user = requireUser(firebaseUid);
        SupportMessageEntity message = supportTicketService.userReply(
                user, ticketId, request.content(), request.attachmentKeys());
        List<SupportAttachmentResponse> attachments =
                attachmentService.responsesFor(List.of(message.getId()))
                        .getOrDefault(message.getId(), List.of());
        return SupportMessageResponse.from(message, attachments);
    }

    @PostMapping("/attachments")
    public Map<String, String> uploadAttachment(@AuthenticationPrincipal String firebaseUid,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        UserEntity user = requireUser(firebaseUid);
        String key = attachmentService.uploadForUser(user.getId(), file);
        return Map.of("key", key,
                "url", storageService.generatePresignedUrl(key, Duration.ofHours(1)));
    }

    @PostMapping("/tickets/{ticketId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal String firebaseUid,
                         @PathVariable UUID ticketId) {
        supportTicketService.markRead(requireUser(firebaseUid), ticketId);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal String firebaseUid) {
        UserEntity user = requireUser(firebaseUid);
        return Map.of("count", supportTicketService.totalUnread(user.getId()));
    }

    private UserEntity requireUser(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
