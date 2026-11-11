package com.yadony.api.dac7.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne de {@code GET /admin/dac7/sellers} (contrat 2). Jamais le NIF, la date de naissance ni l'adresse.
 * {@code remindersSent} et {@code solicitation} sont toujours présents. Les montants ne sont calculés que pour les
 * voyageurs déclarables : ils sont omis (nuls) pour une résidence inconnue ou hors périmètre. {@code identityStatus} :
 * COMPLETE, INCOMPLETE, UNAVAILABLE (prestataire injoignable, réessayable) ou NOT_CHECKED (pas encore lu pendant cet
 * affichage : au-delà de 40 lectures nouvelles ou hors du budget de la page ; le prochain affichage le lira).
 * {@code alreadyExported} : déjà retenu dans un fichier DÉPOSÉ pour l'année (déclarables seulement).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dac7SellerRow(UUID userId, String displayName, String residenceCountry, String residencyStatus,
                            boolean taxInfoProvided, String identityStatus, String solicitation, int remindersSent,
                            Instant lastDiligenceAt, Integer operations, BigDecimal netEur, BigDecimal commissionEur,
                            Boolean eurComplete, Boolean commissionComplete, Boolean alreadyExported) {
}
