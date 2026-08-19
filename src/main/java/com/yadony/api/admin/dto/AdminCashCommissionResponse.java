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
 * {@code net + commission = brut}, mais cet invariant ne vaut que pour les demandes issues
 * d'une NEGOCIATION : elles seules ont un brut et un net figes. Une demande cash ordinaire
 * calcule sa commission depuis {@code kgNet + gridNet}
 * ({@code CashCommissionService.computeBidCommission}) sans jamais renseigner ces colonnes.
 *
 * <p>Les deux montants sont donc {@code null} — et non zero — quand ils ne sont pas
 * derivables. Un zero se lirait comme « aucune commission prelevee », ce qui est faux : la
 * commission a bien ete prelevee, c'est son montant que cette vue ne sait pas reconstituer.
 * Le back-office affiche « — ». Reconstituer le montant reel demanderait de rejouer le calcul
 * complet (annonce + articles de grille), ou de stocker le montant preleve a l'acceptation :
 * c'est la vraie correction, elle merite d'etre faite deliberement.
 *
 * <p>{@code spring.jackson.default-property-inclusion: NON_NULL} : les champs nuls sont donc
 * ABSENTS du JSON, pas presents a null.
 */
public record AdminCashCommissionResponse(
        UUID bidId,
        Long amountCents,
        Long commissionCents,
        String currency,
        CommissionStatus status,
        CommissionChargedVia chargedVia,
        int retryCount,
        LocalDateTime createdAt) {

    public static AdminCashCommissionResponse from(BidEntity bid) {
        BigDecimal gross = bid.getNegotiatedGrossEur();
        BigDecimal net = bid.getNegotiatedNetEur();
        boolean derivable = gross != null && net != null;
        return new AdminCashCommissionResponse(
                bid.getId(),
                derivable ? AdminWalletResponse.toCents(gross) : null,
                derivable ? AdminWalletResponse.toCents(gross.subtract(net)) : null,
                bid.getCurrency(),
                bid.getCommissionStatus(),
                bid.getCommissionChargedVia(),
                bid.getCommissionRetryCount(),
                bid.getCreatedAt());
    }
}
