package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminSupportTicketResponse;
import com.yadony.api.admin.dto.AdminSupportToolsDtos.AddNoteRequest;
import com.yadony.api.admin.dto.AdminSupportToolsDtos.NoteResponse;
import com.yadony.api.admin.dto.AdminSupportToolsDtos.SetPriorityRequest;
import com.yadony.api.admin.dto.AdminSupportToolsDtos.SupportContextResponse;
import com.yadony.api.admin.dto.AdminSupportToolsDtos.TemplateRequest;
import com.yadony.api.admin.dto.AdminSupportToolsDtos.TemplateResponse;
import com.yadony.api.support.SupportAdminToolsService;
import com.yadony.api.support.SupportReplyTemplateEntity;
import com.yadony.api.support.SupportTicketNoteEntity;
import com.yadony.api.support.SupportTicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Outils des agents du support pour le poste de traitement : priorité d'un ticket, contexte (historique
 * tracé et notes internes), notes, réponses types. Lecture avec SUPPORT_TICKET_VIEW, gestes avec
 * SUPPORT_TICKET_MANAGE.
 */
@RestController
@RequestMapping("/admin/support")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSupportToolsController {

    static final String TICKET_ENTITY = "support_ticket";

    private final SupportTicketService ticketService;
    private final SupportAdminToolsService tools;
    private final AdminSupportTicketViews views;
    private final ReportContextService reportContext;
    private final AdminUserRepository adminUserRepository;

    public AdminSupportToolsController(SupportTicketService ticketService, SupportAdminToolsService tools,
                                       AdminSupportTicketViews views, ReportContextService reportContext,
                                       AdminUserRepository adminUserRepository) {
        this.ticketService = ticketService;
        this.tools = tools;
        this.views = views;
        this.reportContext = reportContext;
        this.adminUserRepository = adminUserRepository;
    }

    @PostMapping("/tickets/{ticketId}/priority")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public AdminSupportTicketResponse setPriority(Authentication auth, @PathVariable UUID ticketId,
                                                  @Valid @RequestBody SetPriorityRequest request) {
        return views.detail(ticketService.changePriority(ticketId, request.priority(), adminId(auth)));
    }

    @GetMapping("/tickets/{ticketId}/context")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_VIEW')")
    public SupportContextResponse context(@PathVariable UUID ticketId) {
        List<SupportTicketNoteEntity> notes = tools.listNotes(ticketId);
        Map<UUID, String> emails = emailsOf(notes);
        return new SupportContextResponse(
                reportContext.history(TICKET_ENTITY, ticketId),
                notes.stream().map(n -> noteOf(n, emails)).toList());
    }

    @PostMapping("/tickets/{ticketId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public NoteResponse addNote(Authentication auth, @PathVariable UUID ticketId,
                                @Valid @RequestBody AddNoteRequest request) {
        SupportTicketNoteEntity note = tools.addNote(ticketId, adminId(auth), request.content());
        return noteOf(note, emailsOf(List.of(note)));
    }

    @GetMapping("/reply-templates")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_VIEW')")
    public List<TemplateResponse> templates() {
        return tools.listTemplates().stream().map(AdminSupportToolsController::templateOf).toList();
    }

    @PostMapping("/reply-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public TemplateResponse createTemplate(Authentication auth, @Valid @RequestBody TemplateRequest request) {
        return templateOf(tools.createTemplate(adminId(auth), request.label(), request.content()));
    }

    @PutMapping("/reply-templates/{templateId}")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public TemplateResponse updateTemplate(Authentication auth, @PathVariable UUID templateId,
                                           @Valid @RequestBody TemplateRequest request) {
        return templateOf(tools.updateTemplate(templateId, adminId(auth), request.label(), request.content()));
    }

    @DeleteMapping("/reply-templates/{templateId}")
    @PreAuthorize("hasAuthority('SUPPORT_TICKET_MANAGE')")
    public ResponseEntity<Void> deleteTemplate(Authentication auth, @PathVariable UUID templateId) {
        tools.deleteTemplate(templateId, adminId(auth));
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- privées

    private Map<UUID, String> emailsOf(List<SupportTicketNoteEntity> notes) {
        List<UUID> ids = notes.stream().map(SupportTicketNoteEntity::getAdminId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return adminUserRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AdminUserEntity::getId, AdminUserEntity::getEmail, (a, b) -> a, java.util.HashMap::new));
    }

    private static NoteResponse noteOf(SupportTicketNoteEntity n, Map<UUID, String> emails) {
        return new NoteResponse(n.getId(), emails.get(n.getAdminId()), n.getContent(), n.getCreatedAt());
    }

    private static TemplateResponse templateOf(SupportReplyTemplateEntity t) {
        return new TemplateResponse(t.getId(), t.getLabel(), t.getContent(), t.getSortOrder());
    }

    private static UUID adminId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new com.yadony.api.common.YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Unauthorized", "Un compte administrateur est requis");
        }
        return principal.adminId();
    }
}
