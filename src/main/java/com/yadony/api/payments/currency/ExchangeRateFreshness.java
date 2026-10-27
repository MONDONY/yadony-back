package com.yadony.api.payments.currency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Dit si le taux d'une devise est trop ancien pour engager de l'argent (prélever une commission sur
 * un portefeuille converti). Estimer un total à l'écran n'engage rien et tolère un vieux taux ;
 * dépenser engage, un taux périmé fait perdre de l'argent à Yadony ou au voyageur.
 *
 * <p>Seules les devises flottantes (USD, CAD, GBP, CHF) périment : la synchronisation BCE les met
 * à jour chaque jour à 07 h UTC. L'euro est le pivot, le F CFA (XOF, XAF) est arrimé à l'euro à un
 * taux fixe (655,957) : ils ne périment jamais. Seuil {@code yadony.exchange-rates.max-age-hours},
 * 48 h par défaut.
 *
 * <p>Pas de cache : une lecture par clé primaire, uniquement pour les portefeuilles à solde positif
 * d'un voyageur qui règle une commission. La synchronisation ne purge pas un cache d'âge.
 */
@Component
public class ExchangeRateFreshness {

    /** Devises dont le taux est fixe ou pivot : jamais périmées. */
    private static final Set<String> PEGGED = Set.of("EUR", "XOF", "XAF");

    private final ExchangeRateRepository repository;
    private final Duration maxAge;

    public ExchangeRateFreshness(ExchangeRateRepository repository,
                                 @Value("${yadony.exchange-rates.max-age-hours:48}") long maxAgeHours) {
        this.repository = repository;
        this.maxAge = Duration.ofHours(maxAgeHours);
    }

    /** Vrai quand le taux de cette devise flottante a plus de {@code max-age-hours}. Taux absent : périmé. */
    public boolean isStale(String currency) {
        return isStale(currency, Instant.now());
    }

    boolean isStale(String currency, Instant now) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (PEGGED.contains(code)) {
            return false;
        }
        return repository.findByCurrency(code)
                .map(rate -> rate.getUpdatedAt() == null
                        || Duration.between(rate.getUpdatedAt().toInstant(), now).compareTo(maxAge) > 0)
                .orElse(true);
    }
}
