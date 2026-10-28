package com.yadony.api.admin.dto;

import java.math.BigDecimal;

/**
 * Nouveau taux d'une devise, en unites pour un euro.
 *
 * <p>Aucune annotation Bean Validation ici volontairement : les bornes (positif, non nul,
 * plafond raisonnable) sont verifiees dans {@code AdminExchangeRateController} et rejetees via
 * {@code YadonyBusinessException} pour porter un {@code errorCode} exploitable par les clients
 * admin, contrairement a un {@code MethodArgumentNotValidException} generique.
 */
public record UpdateExchangeRateRequest(BigDecimal unitsPerEur, Boolean confirmLargeChange) {

    /** Ancien contrat (écran admin sans confirmation) : la garde de variation s'applique. */
    public UpdateExchangeRateRequest(BigDecimal unitsPerEur) {
        this(unitsPerEur, null);
    }
}
