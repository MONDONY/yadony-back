package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminUserListItemResponse(
        UUID id,
        String firstName,
        String lastName,
        String phoneNumber,
        String email,
        String city,
        String country,
        String status,
        String kycStatus,
        boolean isProAccount,
        BigDecimal averageRating,
        int totalTrips,
        int totalShipments,
        LocalDateTime createdAt
) {
    /** Le téléphone et l'email proviennent de Firebase : ils ne sont plus stockés en base. */
    public static AdminUserListItemResponse from(UserEntity u, FirebaseContactService.Contact contact) {
        return new AdminUserListItemResponse(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                contact.phoneNumber(),
                contact.email(),
                u.getCity(),
                u.getCountry(),
                u.getStatus().name(),
                u.getKycStatus().name(),
                u.isProAccount(),
                u.getAverageRating(),
                u.getTotalTrips(),
                u.getTotalShipments(),
                u.getCreatedAt()
        );
    }
}
