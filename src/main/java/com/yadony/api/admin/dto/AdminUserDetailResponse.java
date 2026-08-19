package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminUserDetailResponse(
        UUID id,
        String firstName,
        String lastName,
        String phoneNumber,
        String city,
        String country,
        String status,
        String kycStatus,
        boolean isProAccount,
        BigDecimal averageRating,
        int totalTrips,
        int totalShipments,
        LocalDateTime createdAt,
        String email,
        List<String> roles,
        String stripeAccountStatus,
        BigDecimal commissionRateOverride,
        boolean publishingSuspended,
        boolean kiloPro,
        int cancellationCount,
        int noShowCount,
        int refusedCount,
        int senderHandoverIncidentCount,
        int ratingCount,
        LocalDateTime deletionRequestedAt,
        LocalDateTime messagingMutedUntil
) {
    /** Téléphone et email proviennent de Firebase : ils ne sont plus stockés en base. */
    public static AdminUserDetailResponse from(UserEntity u, FirebaseContactService.Contact contact) {
        return new AdminUserDetailResponse(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                contact.phoneNumber(),
                u.getCity(),
                u.getCountry(),
                u.getStatus().name(),
                u.getKycStatus().name(),
                u.isProAccount(),
                u.getAverageRating(),
                u.getTotalTrips(),
                u.getTotalShipments(),
                u.getCreatedAt(),
                contact.email(),
                u.getRoles().stream().map(Enum::name).toList(),
                u.getStripeAccountStatus() != null ? u.getStripeAccountStatus().name() : null,
                u.getCommissionRateOverride(),
                u.isPublishingSuspended(),
                u.isKiloPro(),
                u.getCancellationCount(),
                u.getNoShowCount(),
                u.getRefusedCount(),
                u.getSenderHandoverIncidentCount(),
                u.getRatingCount(),
                u.getDeletionRequestedAt() != null
                        ? java.time.LocalDateTime.ofInstant(u.getDeletionRequestedAt(), java.time.ZoneOffset.UTC)
                        : null,
                u.getMessagingMutedUntil() != null
                        ? java.time.LocalDateTime.ofInstant(u.getMessagingMutedUntil(), java.time.ZoneOffset.UTC)
                        : null
        );
    }
}
