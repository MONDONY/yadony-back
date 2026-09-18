package com.yadony.api.payments.wallet;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Convertit chaque portefeuille d'un utilisateur dans sa devise active pour afficher un
 * total estimé en tête de l'écran portefeuille. L'argent n'est jamais converti : chaque
 * devise garde son solde (décision D1 du chantier « recharge mobile money »).
 *
 * <p>Tolérant par devise : une devise absente de {@code exchange_rates}
 * ({@code exchange-rate-missing}) est exclue du total et signalée par
 * {@link WalletEstimate#complete()} à {@code false}, l'écran ne casse jamais.
 */
@Service
public class WalletEstimateService {

    private static final Logger log = LoggerFactory.getLogger(WalletEstimateService.class);

    private final ExchangeRateService exchangeRateService;

    public WalletEstimateService(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    public WalletEstimate estimate(List<WalletAccountEntity> wallets, String activeCurrency) {
        String active = activeCurrency.trim().toUpperCase(Locale.ROOT);
        int decimals = SupportedCurrency.fromCodeOrDefault(active).minorUnit();
        Map<String, BigDecimal> inActive = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        boolean anyConverted = false;

        for (WalletAccountEntity wallet : wallets) {
            String currency = wallet.getCurrency().trim().toUpperCase(Locale.ROOT);
            try {
                BigDecimal converted = exchangeRateService.convert(wallet.getBalance(), wallet.getCurrency(), active);
                inActive.put(currency, converted);
                total = total.add(converted);
                anyConverted = true;
            } catch (YadonyBusinessException e) {
                if (!"exchange-rate-missing".equals(e.getErrorCode())) {
                    throw e;
                }
                log.warn("Taux de change absent pour {} : devise exclue du total estimé", currency);
                complete = false;
            }
        }

        if (!anyConverted && !wallets.isEmpty()) {
            return new WalletEstimate(Map.of(), null, false);
        }
        return new WalletEstimate(inActive, total.setScale(decimals, RoundingMode.HALF_UP), complete);
    }
}
