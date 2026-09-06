package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminStatus;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminSupportTicketResponse;
import com.yadony.api.admin.dto.ReassignSupportTicketRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.support.SupportAttachmentService;
import com.yadony.api.support.SupportMessageEntity;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketScope;
import com.yadony.api.support.SupportTicketService;
import com.yadony.api.support.SupportTicketStatus;
import com.yadony.api.support.dto.CreateSupportMessageRequest;
import com.yadony.api.support.dto.SupportAttachmentResponse;
import com.yadony.api.support.dto.SupportMessageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * File support du back-office. Repondre et resoudre sont reserves a l'admin
 * assigne : c'est la garantie que deux personnes ne traitent pas le meme ticket
 * en parallele. Reprendre le ticket d'un collegue passe par {@code /reassign}.
 */
@RestController
@RequestMapping("/admin/support/tickets")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSupportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SupportTicketService supportTicketService;
    private final SupportAttachmentService attachmentService;
    private final StorageService storageService;
    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;

    public AdminSupportController(SupportTicketService supportTicketService,
                                  SupportAttachmentService attachmentService,
                                  StorageService storageService,
                                  UserRepository userRepository,
                                  AdminUserRepository adminUserRepository) {
        this.supportTicketService = supportTicketService;
        this.attachmentService = attachmentService;
        this.storageService = storageService;
        this.userRepository = userRepository;
        this.adminUserRepository = adminUserRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_VIEW')")
    public Page<AdminSupportTicketResponse> list(Authentication auth,
                                                 @RequestParam(defaultValue = "all") String scope,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        UUID adminId = adminId(auth);
        Page<SupportTicketEntity> tickets = supportTicketService.listAdminTickets(
                parseScope(scope),
                parseStatus(status),
                adminId,
                PageRequest.of(Math.max(page, 0), clampSize(size)));

        Map<UUID, UserEntity> users = loadUsers(tickets.getContent());
        Map<UUID, String> adminEmails = loadAdminEmails(tickets.getContent());
        return tickets.map(ticket -> AdminSupportTicketResponse.summary(
                ticket,
                users.get(ticket.getUserId()),
                ticket.getAssignedAdminId() == null ? null
                        : adminEmails.get(ticket.getAssignedAdminId())));
    }

    @GetMapping("/{ticketId}")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_VIEW')")
    public AdminSupportTicketResponse get(@PathVariable UUID ticketId) {
        return detail(supportTicketService.getTicketForAdmin(ticketId));
    }

    @PostMapping("/{ticketId}/assign")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public AdminSupportTicketResponse assign(Authentication auth, @PathVariable UUID ticketId) {
        return detail(supportTicketService.assign(ticketId, adminId(auth)));
    }

    @PostMapping("/{ticketId}/reassign")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public AdminSupportTicketResponse reassign(Authentication auth,
                                               @PathVariable UUID ticketId,
                                               @Valid @RequestBody ReassignSupportTicketRequest request) {
        requireActiveAdmin(request.adminId());
        return detail(supportTicketService.reassign(ticketId, request.adminId(), adminId(auth)));
    }

    @PostMapping("/{ticketId}/messages")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public SupportMessageResponse reply(Authentication auth,
                                        @PathVariable UUID ticketId,
                                        @Valid @RequestBody CreateSupportMessageRequest request) {
        UUID adminId = adminId(auth);
        SupportMessageEntity message = supportTicketService.adminReply(
                ticketId, adminId, request.content(), request.attachmentKeys());
        List<SupportAttachmentResponse> attachments =
                attachmentService.responsesFor(List.of(message.getId()))
                        .getOrDefault(message.getId(), List.of());
        return SupportMessageResponse.from(message, attachments);
    }

    @PostMapping("/attachments")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public Map<String, String> uploadAttachment(Authentication auth,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        UUID adminId = adminId(auth);
        String key = attachmentService.uploadForAdmin(adminId, file);
        return Map.of("key", key,
                "url", storageService.generatePresignedUrl(key, Duration.ofHours(1)));
    }

    @PostMapping("/{ticketId}/resolve")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public AdminSupportTicketResponse resolve(Authentication auth, @PathVariable UUID ticketId) {
        return detail(supportTicketService.resolve(ticketId, adminId(auth)));
    }

    // ---------------------------------------------------------------- privees

    private AdminSupportTicketResponse detail(SupportTicketEntity ticket) {
        List<SupportMessageEntity> messages = supportTicketService.listMessages(ticket.getId());
        List<UUID> messageIds = messages.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<SupportAttachmentResponse>> attachMap = attachmentService.responsesFor(messageIds);
        UserEntity user = userRepository.findById(ticket.getUserId()).orElse(null);
        String adminEmail = ticket.getAssignedAdminId() == null ? null
                : adminUserRepository.findById(ticket.getAssignedAdminId())
                        .map(AdminUserEntity::getEmail)
                        .orElse(null);
        return AdminSupportTicketResponse.withMessages(ticket, user, adminEmail, messages, attachMap);
    }

    private Map<UUID, UserEntity> loadUsers(List<SupportTicketEntity> tickets) {
        Set<UUID> ids = tickets.stream().map(SupportTicketEntity::getUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    private Map<UUID, String> loadAdminEmails(List<SupportTicketEntity> tickets) {
        Set<UUID> ids = tickets.stream()
                .map(SupportTicketEntity::getAssignedAdminId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return adminUserRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AdminUserEntity::getId, AdminUserEntity::getEmail));
    }

    private void requireActiveAdmin(UUID targetAdminId) {
        AdminUserEntity target = adminUserRepository.findById(targetAdminId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "admin-not-found", "Administrateur introuvable",
                        "Aucun compte administrateur ne correspond a cet identifiant"));
        if (target.getStatus() == AdminStatus.DISABLED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "admin-disabled", "Administrateur desactive",
                    "Impossible d'assigner un ticket a un compte desactive");
        }
    }

    private static UUID adminId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Unauthorized", "Un compte administrateur est requis");
        }
        return principal.adminId();
    }

    private static SupportTicketScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SupportTicketScope.ALL;
        }
        try {
            return SupportTicketScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-invalid-scope", "Filtre invalide",
                    "Scope inconnu: " + scope);
        }
    }

    private static SupportTicketStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SupportTicketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-invalid-status", "Filtre invalide",
                    "Statut inconnu: " + status);
        }
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
