package com.yadony.api.admin.dto;

import com.yadony.api.admin.broadcast.AdminBroadcastEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBroadcastResponse(
        UUID id,
        String title,
        String body,
        String targetType,
        String targetOrigin,
        String targetDestination,
        UUID targetUserId,
        int recipientCount,
        UUID adminId,
        LocalDateTime createdAt) {

    public static AdminBroadcastResponse from(AdminBroadcastEntity entity) {
        return new AdminBroadcastResponse(
                entity.getId(), entity.getTitle(), entity.getBody(),
                entity.getTargetType().name(), entity.getTargetOrigin(), entity.getTargetDestination(),
                entity.getTargetUserId(), entity.getRecipientCount(), entity.getAdminId(),
                entity.getCreatedAt());
    }
}
