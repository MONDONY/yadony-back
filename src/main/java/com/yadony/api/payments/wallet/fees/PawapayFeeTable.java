package com.yadony.api.payments.wallet.fees;

import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Barème des frais pawaPay (dépôt + remboursement) appliqué au remboursement d'une
 * recharge mobile money : {@code defaults} s'applique tant qu'aucune surcharge n'est
 * déclarée sous {@code providers} pour l'opérateur demandé (voir application.yml,
 * {@code yadony.pawapay.fees}).
 *
 * <p>{@code default} est un mot réservé Java : le composant se nomme {@code defaults}
 * et est relié à la clé YAML {@code default} via {@link Name}.
 *
 * <p>La recherche d'opérateur normalise les clés (majuscules, caractères non
 * alphanumériques retirés) des deux côtés avant comparaison : Spring aplatit les clés
 * de map YAML non crochetées et peut en retirer les séparateurs (ex. {@code ORANGE_CIV}
 * devient {@code ORANGECIV} une fois lié), donc comparer les clés telles quelles
 * manquerait la surcharge.
 */
@ConfigurationProperties(prefix = "yadony.pawapay.fees")
public record PawapayFeeTable(@Name("default") Rate defaults, Map<String, Rate> providers) {

    public record Rate(BigDecimal depositPercent, BigDecimal refundPercent) {}

    private static final Rate HARDCODED_DEFAULT = new Rate(BigDecimal.ONE, BigDecimal.ONE);

    /** Frais pawaPay = {@code amount x (depositPercent + refundPercent) / 100}, arrondi à l'échelle de la devise. */
    public BigDecimal fee(String provider, BigDecimal amount, String currency) {
        Rate rate = rateFor(provider);
        int scale = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        return amount.multiply(rate.depositPercent().add(rate.refundPercent()))
                .divide(new BigDecimal("100"), scale, RoundingMode.HALF_UP);
    }

    private Rate rateFor(String provider) {
        Rate base = defaults != null ? defaults : HARDCODED_DEFAULT;
        if (provider == null || providers == null || providers.isEmpty()) {
            return base;
        }
        String needle = normalize(provider);
        for (Map.Entry<String, Rate> entry : providers.entrySet()) {
            if (normalize(entry.getKey()).equals(needle)) {
                return entry.getValue();
            }
        }
        return base;
    }

    private static String normalize(String key) {
        return key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
