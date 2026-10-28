package com.yadony.api.payments.currency;

import com.yadony.api.admin.AdminAlertEscalator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateFreshness.class);

    private final ExchangeRateRepository repository;
    private final AdminAlertEscalator alerts;
    private final Duration maxAge;

    public ExchangeRateFreshness(ExchangeRateRepository repository, AdminAlertEscalator alerts,
                                 @Value("${yadony.exchange-rates.max-age-hours:48}") long maxAgeHours) {
        this.repository = repository;
        this.alerts = alerts;
        this.maxAge = Duration.ofHours(maxAgeHours);
    }

    /**
     * Prévient l'admin qu'un taux périmé écarte des portefeuilles de la dépense. Une seule alerte non
     * résolue par devise ({@code raiseOnce}, dans sa propre transaction) : le voyageur suivant n'en
     * relance pas une de plus. Au mieux : une alerte qui échoue ne doit jamais bloquer un prélèvement.
     */
    public void alertStale(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        try {
            alerts.raiseOnce("EXCHANGE_RATE_STALE_" + code,
                    "Le taux " + code + " a plus de " + maxAge.toHours() + " h : les portefeuilles en " + code
                            + " sont écartés du prélèvement des commissions tant qu'il n'est pas mis à jour.",
                    Map.of("currency", code, "maxAgeHours", maxAge.toHours()));
        } catch (RuntimeException e) {
            log.warn("Alerte de taux périmé {} non levée : {}", code, e.getMessage());
        }
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
