package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CurrencyMatchGuard {

    private static final String UNKNOWN = "UNKNOWN";

    public void assertMatches(String listingCurrency, String actorCurrency) {
        if (matches(listingCurrency, actorCurrency)) {
            return;
        }

        throw new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "currency-mismatch",
                "Currency Mismatch",
                "Ce trajet est publié en " + displayCurrency(listingCurrency)
                        + ", ton compte est en " + displayCurrency(actorCurrency) + ".",
                Map.of(
                        "listingCurrency", displayCurrency(listingCurrency),
                        "actorCurrency", displayCurrency(actorCurrency)));
    }

    private boolean matches(String listingCurrency, String actorCurrency) {
        return listingCurrency != null
                && actorCurrency != null
                && listingCurrency.equalsIgnoreCase(actorCurrency);
    }

    private String displayCurrency(String currency) {
        return currency != null ? currency : UNKNOWN;
    }
}
