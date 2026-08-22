package com.yadony.api.auth.dto;

import com.yadony.api.auth.StripeAccountStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * @param username identifiant public généré à la création (« user » + horodatage). Toujours
 *        présent : le client s'en sert comme nom de repli quand {@code firstName} est vide,
 *        au lieu d'afficher le numéro de téléphone ou l'email du compte.
 */
public record UserResponse(
    UUID id,
    String username,
    String phoneNumber,
    String email,
    String firstName,
    String lastName,
    LocalDate birthDate,
    String city,
    Set<String> roles,
    String kycStatus,
    String status,
    int totalTrips,
    int totalShipments,
    Boolean isProAccount,
    StripeAccountStatus stripeAccountStatus,
    String country,
    String bio,
    Set<String> languages,
    String transportMode,
    String avatarUrl,
    Double averageRating,
    AdminInfo admin,
    String residenceStreet,
    String residenceLine2,
    String residencePostalCode,
    String onboardingSeenAt
) {}
