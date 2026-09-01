package com.yadony.api.payments.currency;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Taux administrés courants de {@code exchange_rates}, servis à l'application mobile.
 *
 * <p>Ferme la triplication des taux : le catalogue Dart embarquait sa propre copie de
 * {@code unitsPerEur}, figée à la compilation — dès qu'un taux bougeait côté serveur
 * (back-office ou synchronisation BCE), les bornes de saisie et de filtre du client
 * divergeaient de ce que l'API accepte. L'app charge ces taux au démarrage (pattern
 * {@code /config/reimbursement-cap}) et ne garde ses constantes qu'en repli hors
 * ligne.
 *
 * <p>Route sous {@code /config} : public comme ses voisins (un taux de change ne
 * révèle rien de sensible, et l'app le lit avant toute session), mais le code reste
 * dans le package {@code payments.currency} — un controller par feature, la règle du
 * projet. La FORME de la réponse ne doit jamais changer : consommée par des clients
 * mobiles déjà installés ({@code CurrencyRatesControllerIT} la verrouille).
 */
@RestController
@RequestMapping("/config")
public class CurrencyRatesController {

    private final ExchangeRateRepository exchangeRateRepository;

    public CurrencyRatesController(ExchangeRateRepository exchangeRateRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
    }

    @GetMapping("/exchange-rates")
    public PublicRatesResponse getExchangeRates() {
        List<PublicRateResponse> rates = exchangeRateRepository.findAll().stream()
                .map(e -> new PublicRateResponse(e.getCurrency(), e.getUnitsPerEur()))
                .sorted(Comparator.comparing(PublicRateResponse::currency))
                .toList();
        return new PublicRatesResponse(rates);
    }

    /** Unités de {@code currency} pour un euro — même sémantique que la table. */
    public record PublicRateResponse(String currency, BigDecimal unitsPerEur) {}

    public record PublicRatesResponse(List<PublicRateResponse> rates) {}
}
