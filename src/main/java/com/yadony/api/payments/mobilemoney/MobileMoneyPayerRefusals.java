package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import org.springframework.http.HttpStatus;

/**
 * Refus 422 {@code mobile-money-payer-unsupported} (numéro ou réseau inutilisable pour payer un colis,
 * un supplément ou une négociation en mobile money). Titre et détail viennent des clés
 * {@code problem.mobile-money-payer-unsupported.<motif>}, résolues selon la langue de la requête.
 */
public final class MobileMoneyPayerRefusals {

    private static final String CODE = "mobile-money-payer-unsupported";

    private MobileMoneyPayerRefusals() {
    }

    /** Numéro reconnu mais inexploitable : le motif dépend de {@code e.reason()}. */
    public static YadonyBusinessException of(PawapayProviderResolver.UnsupportedNumberException e, String currency) {
        return switch (e.reason()) {
            case NO_PROVIDER -> localized("problem.mobile-money-payer-unsupported.no-provider");
            case OPERATION_CLOSED -> localized("problem.mobile-money-payer-unsupported.operation-closed", e.providerLabel());
            case CURRENCY_MISMATCH -> localized("problem.mobile-money-payer-unsupported.currency-mismatch", e.providerCurrency(), currency);
            case COUNTRY_UNKNOWN -> localized("problem.mobile-money-payer-unsupported.country-unknown");
            case PROVIDER_NOT_AVAILABLE -> localized("problem.mobile-money-payer-unsupported.network-unavailable", e.providerLabel());
        };
    }

    public static YadonyBusinessException amountOutOfRange(String providerLabel) {
        return localized("problem.mobile-money-payer-unsupported.amount-out-of-range", providerLabel);
    }

    public static YadonyBusinessException invalidPhone() {
        return localized("problem.mobile-money-payer-unsupported.invalid-phone");
    }

    public static YadonyBusinessException savedPhoneUnusable() {
        return localized("problem.mobile-money-payer-unsupported.saved-phone-unusable");
    }

    private static YadonyBusinessException localized(String key, Object... args) {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, CODE, key, args);
    }
}
