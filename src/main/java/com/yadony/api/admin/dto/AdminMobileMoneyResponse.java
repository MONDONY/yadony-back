package com.yadony.api.admin.dto;

import com.yadony.api.payments.mobilemoney.MobileMoneyPaymentEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un paiement Mobile Money, en lecture seule.
 *
 * <p>⚠️ Le numero de telephone est masque <b>ici, cote serveur</b>, pas seulement a
 * l'affichage : {@code mobile_money_payments.phone_number} est une donnee personnelle
 * stockee en clair, l'ecran d'administration ne l'affiche jamais en entier, et rien ne
 * justifie donc de l'envoyer au navigateur — ou il finirait dans l'onglet reseau, la memoire
 * de l'onglet et toute capture d'ecran de debogage. Le back-office remasque de son cote :
 * la fonction est idempotente, les deux protections se cumulent sans se contredire.
 */
public record AdminMobileMoneyResponse(
        UUID id,
        UUID bidId,
        String provider,
        String countryCode,
        String phoneNumber,
        long amountCents,
        String currency,
        String status,
        LocalDateTime createdAt) {

    /** Nombre de chiffres laisses visibles — de quoi rapprocher une ligne d'un ticket support. */
    private static final int VISIBLE_DIGITS = 4;

    public static AdminMobileMoneyResponse from(MobileMoneyPaymentEntity entity) {
        return new AdminMobileMoneyResponse(
                entity.getId(),
                entity.getBidId(),
                entity.getProvider(),
                entity.getCountryCode(),
                mask(entity.getPhoneNumber()),
                AdminWalletResponse.toCents(entity.getAmount()),
                entity.getCurrency(),
                entity.getStatus(),
                entity.getCreatedAt());
    }

    static String mask(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() <= VISIBLE_DIGITS) {
            return phoneNumber;
        }
        return "•".repeat(phoneNumber.length() - VISIBLE_DIGITS)
                + phoneNumber.substring(phoneNumber.length() - VISIBLE_DIGITS);
    }
}
