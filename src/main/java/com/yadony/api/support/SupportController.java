package com.yadony.api.support;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.support.dto.CreateSupportMessageRequest;
import com.yadony.api.support.dto.CreateSupportTicketRequest;
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

import java.util.List;
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
    private final UserRepository userRepository;

    public SupportController(SupportTicketService supportTicketService, UserRepository userRepository) {
        this.supportTicketService = supportTicketService;
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
                .map(SupportTicketResponse::summary);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicketResponse createTicket(@AuthenticationPrincipal String firebaseUid,
                                              @Valid @RequestBody CreateSupportTicketRequest request) {
        UserEntity user = requireUser(firebaseUid);
        SupportTicketEntity ticket = supportTicketService.createTicket(
                user, request.category(), request.subject(), request.message());
        return SupportTicketResponse.withMessages(ticket, supportTicketService.listMessages(ticket.getId()));
    }

    @GetMapping("/tickets/{ticketId}")
    public SupportTicketResponse getTicket(@AuthenticationPrincipal String firebaseUid,
                                           @PathVariable UUID ticketId) {
        UserEntity user = requireUser(firebaseUid);
        SupportTicketEntity ticket = supportTicketService.getUserTicket(user, ticketId);
        return SupportTicketResponse.withMessages(ticket, supportTicketService.listMessages(ticketId));
    }

    @PostMapping("/tickets/{ticketId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageResponse addMessage(@AuthenticationPrincipal String firebaseUid,
                                             @PathVariable UUID ticketId,
                                             @Valid @RequestBody CreateSupportMessageRequest request) {
        UserEntity user = requireUser(firebaseUid);
        return SupportMessageResponse.from(
                supportTicketService.userReply(user, ticketId, request.content()));
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
