package com.yadony.api.admin.dto;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.support.SupportMessageEntity;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.dto.SupportMessageResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Vue back-office d'un ticket. Expose l'identite de l'utilisateur (le support ne
 * peut pas traiter un ticket sans savoir qui l'a ouvert) mais jamais son numero
 * de telephone ni son email — le back-office a des ecrans dedies pour cela.
 */
public record AdminSupportTicketResponse(
        UUID id,
        String category,
        String subject,
        String status,
        String priority,
        UUID userId,
        String userDisplayName,
        UUID assignedAdminId,
        String assignedAdminEmail,
        LocalDateTime createdAt,
        LocalDateTime lastMessageAt,
        LocalDateTime resolvedAt,
        List<SupportMessageResponse> messages) {

    public static AdminSupportTicketResponse summary(SupportTicketEntity ticket,
                                                     UserEntity user,
                                                     String assignedAdminEmail) {
        return build(ticket, user, assignedAdminEmail, null);
    }

    public static AdminSupportTicketResponse withMessages(SupportTicketEntity ticket,
                                                          UserEntity user,
                                                          String assignedAdminEmail,
                                                          List<SupportMessageEntity> messages) {
        return build(ticket, user, assignedAdminEmail,
                messages.stream().map(SupportMessageResponse::from).toList());
    }

    private static AdminSupportTicketResponse build(SupportTicketEntity ticket,
                                                    UserEntity user,
                                                    String assignedAdminEmail,
                                                    List<SupportMessageResponse> messages) {
        return new AdminSupportTicketResponse(
                ticket.getId(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getStatus().name(),
                ticket.getPriority().name(),
                ticket.getUserId(),
                displayName(user),
                ticket.getAssignedAdminId(),
                assignedAdminEmail,
                ticket.getCreatedAt(),
                ticket.getLastMessageAt(),
                ticket.getResolvedAt(),
                messages);
    }

    /**
     * Un compte supprime (soft delete) sort du {@code @Where} de UserEntity : le
     * ticket reste lisible, sans nom, plutot que de faire echouer la liste.
     */
    private static String displayName(UserEntity user) {
        if (user == null) {
            return UserEntity.UNKNOWN_DISPLAY_NAME;
        }
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank() && last != null && !last.isBlank()) {
            return first + " " + last;
        }
        if (first != null && !first.isBlank()) {
            return first;
        }
        return user.getUsername() == null ? UserEntity.UNKNOWN_DISPLAY_NAME : user.getUsername();
    }
}
