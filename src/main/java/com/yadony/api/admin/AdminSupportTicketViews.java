package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminSupportTicketResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.support.SupportAttachmentService;
import com.yadony.api.support.SupportMessageEntity;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketService;
import com.yadony.api.support.dto.SupportAttachmentResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vues back-office d'un ticket support, partagées par la page Support et la réponse à
 * un signalement : nom de l'utilisateur, email de l'admin assigné, messages avec pièces
 * jointes présignées, et signalement source ({@code reports.support_ticket_id}).
 */
@Component
public class AdminSupportTicketViews {

    private final SupportTicketService supportTicketService;
    private final SupportAttachmentService attachmentService;
    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;
    private final ReportRepository reportRepository;

    public AdminSupportTicketViews(SupportTicketService supportTicketService,
                                   SupportAttachmentService attachmentService,
                                   UserRepository userRepository,
                                   AdminUserRepository adminUserRepository,
                                   ReportRepository reportRepository) {
        this.supportTicketService = supportTicketService;
        this.attachmentService = attachmentService;
        this.userRepository = userRepository;
        this.adminUserRepository = adminUserRepository;
        this.reportRepository = reportRepository;
    }

    public AdminSupportTicketResponse detail(SupportTicketEntity ticket) {
        List<SupportMessageEntity> messages = supportTicketService.listMessages(ticket.getId());
        List<UUID> messageIds = messages.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<SupportAttachmentResponse>> attachMap = attachmentService.responsesFor(messageIds);
        UserEntity user = userRepository.findById(ticket.getUserId()).orElse(null);
        String adminEmail = ticket.getAssignedAdminId() == null ? null
                : adminUserRepository.findById(ticket.getAssignedAdminId())
                        .map(AdminUserEntity::getEmail)
                        .orElse(null);
        return AdminSupportTicketResponse.withMessages(ticket, user, adminEmail, messages, attachMap,
                sourceReports(List.of(ticket)).get(ticket.getId()));
    }

    public Page<AdminSupportTicketResponse> summaries(Page<SupportTicketEntity> tickets) {
        Map<UUID, UserEntity> users = loadUsers(tickets.getContent());
        Map<UUID, String> adminEmails = loadAdminEmails(tickets.getContent());
        Map<UUID, UUID> reports = sourceReports(tickets.getContent());
        return tickets.map(ticket -> AdminSupportTicketResponse.summary(
                ticket,
                users.get(ticket.getUserId()),
                ticket.getAssignedAdminId() == null ? null : adminEmails.get(ticket.getAssignedAdminId()),
                reports.get(ticket.getId())));
    }

    /** ticketId → reportId, en une requête. */
    private Map<UUID, UUID> sourceReports(List<SupportTicketEntity> tickets) {
        Set<UUID> ids = tickets.stream().map(SupportTicketEntity::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return reportRepository.findBySupportTicketIdIn(ids).stream()
                .filter(r -> r.getSupportTicketId() != null && r.getId() != null)
                .collect(Collectors.toMap(ReportEntity::getSupportTicketId, ReportEntity::getId, (a, b) -> a));
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
}
