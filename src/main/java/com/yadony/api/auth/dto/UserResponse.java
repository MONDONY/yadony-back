package com.yadony.api.auth.dto;

import com.yadony.api.auth.StripeAccountStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * @param username identifiant public généré à la création (« user » + horodatage). Toujours
 *        présent : le client s'en sert comme nom de repli quand {@code firstName} est vide,
 *        au lieu d'afficher le numéro de téléphone ou l'email du compte.
 * @param preferredLanguage {@code "fr"} ou {@code "en"} (colonne {@code preferred_language},
 *        V264). Modifiable via {@code PATCH /users/me/preferences}.
 * @param messagingMutedUntil fin ISO-8601 (UTC) de la coupure de messagerie posée par un
 *        administrateur, {@code null} sans coupure en cours. Permet au client d'expliquer
 *        pourquoi l'envoi est refusé au lieu de laisser Firestore échouer en silence
 *        (FLUTTER-CT/CV). Le motif saisi par l'admin n'est jamais exposé.
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
    String avatarUrl,
    Double averageRating,
    AdminInfo admin,
    String residenceStreet,
    String residenceLine2,
    String residencePostalCode,
    String onboardingSeenAt,
    String preferredLanguage,
    String messagingMutedUntil
) {}
