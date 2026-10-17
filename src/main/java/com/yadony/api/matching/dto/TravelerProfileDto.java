package com.yadony.api.matching.dto;

import java.util.UUID;

/**
 * @param acceptsUnverified ce voyageur accepte les offres d'expéditeurs dont
 *        l'identité n'est pas vérifiée (réglage « profils vérifiés uniquement »
 *        désactivé). Permet au client de ne pas barrer la route à un expéditeur
 *        non vérifié que ce voyageur, lui, accepterait : le refus final reste
 *        prononcé par le serveur (403 {@code contact-kyc-required}).
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
        boolean acceptsUnverified
) {}
