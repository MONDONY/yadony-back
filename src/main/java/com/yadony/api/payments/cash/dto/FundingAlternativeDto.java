package com.yadony.api.payments.cash.dto;

import java.math.BigDecimal;

/**
 * Autre portefeuille du voyageur capable de régler, seul, ce qui reste de la commission après
 * le portefeuille de la devise du colis. {@code required} est le montant qui serait prélevé
 * dans {@code currency}, converti au taux du jour : indicatif, recalculé (et snapshoté) au
 * moment du débit si le voyageur choisit cette devise ({@code fundingCurrency}).
 */
public record FundingAlternativeDto(String currency, BigDecimal balance, BigDecimal required) {
}
