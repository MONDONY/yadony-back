package com.yadony.api.payments.mobilemoney.dto;

import com.yadony.api.payments.pawapay.ValidationHint;
import java.math.BigDecimal;
import java.util.List;

/**
 * Catalogue des pays mobile money : {@code available} = pays où au moins un opérateur est ouvert
 * pour l'opération demandée, {@code upcoming} = codes ISO alpha-2 annoncés « bientôt » (pays
 * encore fermés chez pawaPay, liste réglée par configuration).
 */
public record MobileMoneyCountriesResponse(List<CountryOption> available, List<String> upcoming) {

    /** {@code country} en ISO alpha-2 ({@code CI}), {@code currency} en ISO 4217 ({@code XOF}). */
    public record CountryOption(String country, String currency, List<OperatorOption> operators) {}

    /** {@code minAmount} / {@code maxAmount} : bornes pawaPay de l'opération, nulles si non publiées. */
    public record OperatorOption(String code, String label, String brand, ValidationHint validationHint,
                                 BigDecimal minAmount, BigDecimal maxAmount) {}
}
