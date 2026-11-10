package com.yadony.api.matching.dto;

import java.util.UUID;

/**
 * @param acceptsUnverified ce voyageur accepte les offres d'expéditeurs dont
 *        l'identité n'est pas vérifiée (réglage « profils vérifiés uniquement »
 *        désactivé). Permet au client de ne pas barrer la route à un expéditeur
 *        non vérifié que ce voyageur, lui, accepterait : le refus final reste
 *        prononcé par le serveur (403 {@code contact-kyc-required}).
 * @param travelerStatus statut légal déclaré ({@code INDIVIDUAL} ou {@code PROFESSIONAL}),
 *        {@code null} tant qu'il n'est pas déclaré. Absent chez un ancien back : aucune pastille.
 * @param businessName raison sociale, renseignée seulement pour un professionnel
 * @param businessRegistrationNumber numéro d'immatriculation, renseigné seulement pour un
 *        professionnel ; jamais sur les surfaces anonymes ({@link PublicTravelerCard} est une liste
 *        blanche qui ne le reprend pas)
 * @param businessCountry pays d'immatriculation, renseigné seulement pour un professionnel
 */
public record TravelerProfileDto(
        UUID id,
        String displayName,
        Double averageRating,
        Integer totalTrips,
        /** Déprécié : apps antérieures à GP Pro, à retirer. Toujours {@code false}. */
        boolean kiloPro,
        boolean gpPro,
        boolean isProAccount,
        boolean kycVerified,
        String avatarUrl,
        boolean acceptsUnverified,
        String travelerStatus,
        String businessName,
        String businessRegistrationNumber,
        String businessCountry
) {
    /** Forme d'avant le statut légal du voyageur (lot 2) : aucun statut. */
    public TravelerProfileDto(UUID id, String displayName, Double averageRating, Integer totalTrips,
                              boolean kiloPro, boolean gpPro, boolean isProAccount, boolean kycVerified,
                              String avatarUrl, boolean acceptsUnverified) {
        this(id, displayName, averageRating, totalTrips, kiloPro, gpPro, isProAccount, kycVerified,
                avatarUrl, acceptsUnverified, null, null, null, null);
    }
}
