package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CurrencyCatalog {

    public SupportedCurrency resolve(String countryCode, String preferredCode) {
        if (preferredCode != null && !preferredCode.isBlank()) {
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
        return SupportedCurrency.defaultForCountry(countryCode);
    }
}
