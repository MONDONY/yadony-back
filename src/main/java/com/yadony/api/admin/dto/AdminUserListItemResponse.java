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
        LocalDateTime createdAt,
        /** Demande de suppression en cours ; {@code null} sinon. */
        LocalDateTime deletionRequestedAt,
        /** Finalisation prevue de cette demande (demande + delai de grace) ; {@code null} sinon. */
        LocalDateTime deletionScheduledFor,
        /** Compte testeur du mode recette (colonne {@code users.recette_tester}, V303). */
        boolean recetteTester
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
                u.getCreatedAt(),
                toUtc(u.getDeletionRequestedAt()),
                toUtc(com.yadony.api.auth.AccountDeletionScheduler.scheduledFinalization(u.getDeletionRequestedAt())),
                u.isRecetteTester()
        );
    }

    private static LocalDateTime toUtc(java.time.Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC) : null;
    }
}
