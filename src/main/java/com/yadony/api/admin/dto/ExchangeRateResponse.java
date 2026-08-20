package com.yadony.api.admin.dto;

import com.yadony.api.payments.currency.ExchangeRateEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Une ligne de {@code exchange_rates} telle qu'exposee au back-office.
 *
 * <p>{@code updatedAt}/{@code updatedBy} restent nuls pour un taux jamais modifie depuis le
 * seed de la migration V226 (colonne {@code updated_by} nullable) — la table amorce toutes
 * les devises, l'ecran ne doit pas lire ce silence comme une modification.
 */
public record ExchangeRateResponse(
        String currency,
        BigDecimal unitsPerEur,
        OffsetDateTime updatedAt,
        UUID updatedBy) {

    public static ExchangeRateResponse from(ExchangeRateEntity entity) {
        return new ExchangeRateResponse(
                entity.getCurrency(), entity.getUnitsPerEur(), entity.getUpdatedAt(), entity.getUpdatedBy());
    }
}
