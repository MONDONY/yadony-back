package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CurrencyCatalog {

    /**
     * Resolves an explicit currency preference, rejecting anything outside the catalog.
     * An absent or blank preference falls back to {@link SupportedCurrency#EUR}.
     */
    public SupportedCurrency resolve(String preferredCode) {
        if (preferredCode == null || preferredCode.isBlank()) {
            return SupportedCurrency.EUR;
        }
        SupportedCurrency preferred = SupportedCurrency.fromCode(preferredCode);
        if (preferred == null) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "unsupported-currency",
                    "Unsupported currency",
                    "The requested currency is not supported.");
        }
        return preferred;
    }
}
