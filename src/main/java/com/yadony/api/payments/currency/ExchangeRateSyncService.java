package com.yadony.api.payments.currency;

import com.yadony.api.common.stripe.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Synchronisation quotidienne des taux flottants depuis la BCE.
 *
 * <p>Sans elle, {@code exchange_rates} ne bouge que par action admin : le « environ »
 * des prix convertis se dégrade silencieusement avec le marché. Périmètre strict :
 * les devises FLOTTANTES du catalogue (USD, CAD, GBP, CHF) — jamais l'EUR (pivot,
 * taux 1 par définition) ni XOF/XAF (parité fixe par traité, absents du flux BCE et
 * refusés par {@link ExchangeRateUpdateService} de toute façon).
 *
 * <p>Garde-fou : une variation relative au-delà de {@code max-relative-change}
 * (défaut 10 %) n'est PAS appliquée — un décrochage pareil entre deux jours est bien
 * plus probablement un flux corrompu ou un bug qu'un mouvement de marché, et
 * l'appliquer aveuglément fausserait commissions et montants convertis partout.
 * L'admin est alerté ({@link AdminAlertService} : Sentry + Telegram) et tranche à la
 * main depuis le back-office, qui n'a pas ce seuil.
 *
 * <p>Idempotent (règle des schedulers) : taux identique → aucune écriture, aucun
 * repivot, aucun audit. Chaque devise est appliquée dans sa propre transaction
 * ({@code ExchangeRateUpdateService.apply}) : une devise en échec ne bloque pas les
 * autres.
 */
@Service
public class ExchangeRateSyncService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateSyncService.class);

    /** Action d'audit dédiée : l'historique distingue le robot de la main humaine. */
    static final String AUDIT_ACTION = "EXCHANGE_RATE_SYNCED";

    private final EcbRateClient ecbRateClient;
    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateUpdateService updateService;
    private final AdminAlertService adminAlertService;
    private final BigDecimal maxRelativeChange;

    public ExchangeRateSyncService(EcbRateClient ecbRateClient,
                                   ExchangeRateRepository exchangeRateRepository,
                                   ExchangeRateUpdateService updateService,
                                   AdminAlertService adminAlertService,
                                   @Value("${yadony.exchange-rates.max-relative-change:0.10}")
                                   BigDecimal maxRelativeChange) {
        this.ecbRateClient = ecbRateClient;
        this.exchangeRateRepository = exchangeRateRepository;
        this.updateService = updateService;
        this.adminAlertService = adminAlertService;
        this.maxRelativeChange = maxRelativeChange;
    }

    /** @return nombre de devises effectivement mises à jour. */
    public int syncAll() {
        Map<String, BigDecimal> ecbRates = ecbRateClient.fetchDailyRates();
        if (ecbRates.isEmpty()) {
            // Flux injoignable ou illisible : les taux de la veille restent, état correct.
            log.warn("Synchronisation BCE sans effet : aucun taux reçu");
            return 0;
        }

        int updated = 0;
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            if (currency == SupportedCurrency.EUR) {
                continue; // Pivot : 1 par définition, rien à synchroniser.
            }
            String code = currency.code().toUpperCase(java.util.Locale.ROOT);
            if (ExchangeRateUpdateService.FIXED_PARITY_CURRENCIES.contains(code)) {
                continue; // XOF/XAF : parité fixe par traité, hors marché.
            }
            if (syncOne(code, ecbRates.get(code))) {
                updated++;
            }
        }
        log.info("Synchronisation BCE terminée : {} devise(s) mise(s) à jour", updated);
        return updated;
    }

    private boolean syncOne(String code, BigDecimal ecbRate) {
        if (ecbRate == null) {
            // Devise du catalogue absente du flux du jour : on n'invente rien.
            log.warn("Devise {} absente du flux BCE, taux inchangé", code);
            return false;
        }

        BigDecimal current = exchangeRateRepository.findByCurrency(code)
                .map(ExchangeRateEntity::getUnitsPerEur)
                .orElse(null);
        if (current == null) {
            log.warn("Devise {} sans ligne exchange_rates, ignorée", code);
            return false;
        }
        if (current.compareTo(ecbRate) == 0) {
            return false; // Idempotence : rien à écrire, rien à repivoter.
        }

        BigDecimal relativeChange = ecbRate.subtract(current).abs()
                .divide(current, MathContext.DECIMAL64);
        if (relativeChange.compareTo(maxRelativeChange) > 0) {
            // Décrochage suspect : ne pas appliquer, alerter, laisser l'humain trancher.
            adminAlertService.raise("EXCHANGE_RATE_SYNC_REJECTED",
                    "Variation BCE hors garde-fou pour " + code + ", taux NON appliqué",
                    Map.of("currency", code,
                            "currentRate", current.toPlainString(),
                            "ecbRate", ecbRate.toPlainString(),
                            "relativeChange", relativeChange.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            "maxRelativeChange", maxRelativeChange.toPlainString()));
            return false;
        }

        try {
            updateService.apply(code, ecbRate, null, AUDIT_ACTION);
            log.info("Taux {} synchronisé : {} -> {}", code, current, ecbRate);
            return true;
        } catch (RuntimeException e) {
            // Une devise en échec ne doit pas emporter les suivantes.
            adminAlertService.raise("EXCHANGE_RATE_SYNC_FAILED",
                    "Échec d'application du taux BCE pour " + code,
                    Map.of("currency", code, "ecbRate", ecbRate.toPlainString(),
                            "error", String.valueOf(e.getMessage())));
            return false;
        }
    }
}
