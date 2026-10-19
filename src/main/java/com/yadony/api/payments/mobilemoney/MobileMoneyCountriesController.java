package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catalogue des pays mobile money (ouverts et « bientôt ») pour le champ pays de l'app. */
@RestController
@RequestMapping("/payments/mobile-money/countries")
@PreAuthorize("hasAnyRole('TRAVELER', 'SENDER')")
public class MobileMoneyCountriesController {

    private final MobileMoneyCountriesService service;

    public MobileMoneyCountriesController(MobileMoneyCountriesService service) {
        this.service = service;
    }

    /** {@code operation} : {@code DEPOSIT} (défaut, recharge) ou {@code PAYOUT} (versement). */
    @GetMapping
    public MobileMoneyCountriesResponse countries(@RequestParam(name = "operation", defaultValue = "DEPOSIT") String operation) {
        return service.countries(parse(operation));
    }

    private static PawapayOperationKind parse(String operation) {
        String op = operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
        return switch (op) {
            case "DEPOSIT" -> PawapayOperationKind.DEPOSIT;
            case "PAYOUT" -> PawapayOperationKind.PAYOUT;
            default -> throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-operation",
                    "Invalid Operation", "L'opération doit être DEPOSIT ou PAYOUT.");
        };
    }
}
