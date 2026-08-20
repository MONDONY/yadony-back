package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Convertit un montant d'une devise vers une autre au taux administrable stocke dans
 * {@code exchange_rates} (une ligne par devise, unites pour un euro).
 *
 * <p>La table fait autorite : contrairement a {@link SupportedCurrency#unitsPerEur()} qui fige
 * un ordre de grandeur pour dimensionner des baremes internes, {@link #rateOf(String)} lit le
 * taux courant pilote par l'admin et sert a la conversion reelle d'un montant entre deux devises
 * (affichage multidevise d'une annonce).
 */
@Service
public class ExchangeRateService {

    private final ExchangeRateRepository repository;

    public ExchangeRateService(ExchangeRateRepository repository) {
        this.repository = repository;
    }

    /**
     * Nombre d'unites de {@code currency} pour un euro, lu dans la table {@code exchange_rates}.
     *
     * <p>Mis en cache Caffeine ({@code exchange-rates}) : evince explicitement par le service
     * d'administration a chaque modification de taux (tache 11), pas de TTL courte ici car la
     * donnee ne change que par action admin.
     */
    @Cacheable(cacheNames = "exchange-rates", key = "#currency")
    public BigDecimal rateOf(String currency) {
        return repository.findByCurrency(currency)
                .map(ExchangeRateEntity::getUnitsPerEur)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "exchange-rate-missing", "Exchange Rate Missing",
                        "Aucun taux de change n'est configure pour la devise " + currency,
                        Map.of("currency", currency)));
    }

    /**
     * Convertit {@code amount} de {@code from} vers {@code to}, arrondi au nombre de decimales
     * de la devise cible ({@link SupportedCurrency#decimals()}), {@link RoundingMode#HALF_UP}.
     *
     * <p>Meme devise (source et cible identiques, comparaison insensible a la casse) : le montant
     * est rendu inchange, sans aucun appel a la base.
     */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        String normalizedFrom = normalize(from);
        String normalizedTo = normalize(to);

        if (normalizedFrom.equals(normalizedTo)) {
            return amount;
        }

        BigDecimal amountInEur = "EUR".equals(normalizedFrom)
                ? amount
                : amount.divide(rateOf(normalizedFrom), 10, RoundingMode.HALF_UP);

        BigDecimal converted = "EUR".equals(normalizedTo)
                ? amountInEur
                : amountInEur.multiply(rateOf(normalizedTo));

        int decimals = SupportedCurrency.fromCodeOrDefault(normalizedTo).minorUnit();
        return converted.setScale(decimals, RoundingMode.HALF_UP);
    }

    private String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
