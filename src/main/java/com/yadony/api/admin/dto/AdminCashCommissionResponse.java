package com.yadony.api.admin.dto;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une commission sur un reglement en especes, en lecture seule.
 *
 * <p>Il n'existe pas de table dediee : ce sont des colonnes portees par la demande
 * ({@code commission_status}, {@code commission_charged_via}, {@code commission_retry_count}).
 *
 * <p>⚠️ Aucune colonne ne porte le MONTANT de la commission. Il se deduit de l'invariant
 * {@code net + commission = brut}, garanti au centime par {@code BidNegotiationPricing}.
 * Une demande hors negociation n'a ni brut ni net : les deux montants valent alors zero
 * plutot que de faire tomber la page — la ligne reste consultable pour son statut.
 */
public record AdminCashCommissionResponse(
        UUID bidId,
        long amountCents,
        long commissionCents,
        String currency,
        CommissionStatus status,
        CommissionChargedVia chargedVia,
        int retryCount,
        LocalDateTime createdAt) {

    public static AdminCashCommissionResponse from(BidEntity bid) {
        BigDecimal gross = bid.getNegotiatedGrossEur();
        BigDecimal net = bid.getNegotiatedNetEur();
        long amountCents = AdminWalletResponse.toCents(gross);
        long commissionCents = gross == null || net == null
                ? 0L
                : AdminWalletResponse.toCents(gross.subtract(net));
        return new AdminCashCommissionResponse(
                bid.getId(),
                amountCents,
                commissionCents,
                bid.getCurrency(),
                bid.getCommissionStatus(),
                bid.getCommissionChargedVia(),
                bid.getCommissionRetryCount(),
                bid.getCreatedAt());
    }
}
