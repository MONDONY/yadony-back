package com.yadony.api.export.dto;

import com.yadony.api.addressbook.delivery.dto.DeliveryAddressDto;
import com.yadony.api.addressbook.pickup.dto.PickupAddressDto;
import com.yadony.api.addressbook.recipient.dto.RecipientDto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Export RGPD art. 15 (droit d'accès) — v1 : identité + carnet d'adresses + statut KYC.
 * Historique d'activité (annonces, enchères, paiements, notations) volontairement
 * exclu de cette première itération ; à ajouter dans une v2 si nécessaire.
 */
public record UserDataExportDto(
        ProfileExport profile,
        KycExport kyc,
        List<RecipientDto> recipients,
        List<PickupAddressDto> pickupAddresses,
        List<DeliveryAddressDto> deliveryAddresses,
        List<FavoriteExport> favorites,
        LocalDateTime generatedAt
) {
    public record ProfileExport(
            UUID id,
            String firstName,
            String lastName,
            String email,
            String phoneNumber,
            LocalDate birthDate,
            String city,
            String country,
            String residenceStreet,
            String residenceLine2,
            String residencePostalCode,
            String bio,
            String avatarUrl,
            List<String> roles,
            LocalDateTime createdAt
    ) {}

    /** Statut de vérification uniquement — jamais la pièce d'identité déchiffrée. */
    public record KycExport(
            String status,
            String rejectionReason
    ) {}

    public record FavoriteExport(
            String targetType,
            UUID targetId,
            LocalDateTime createdAt
    ) {}
}
