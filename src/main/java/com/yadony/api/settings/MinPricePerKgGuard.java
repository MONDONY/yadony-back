package com.yadony.api.settings;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Applique côté serveur le réglage « Prix minimum par kg » du voyageur
 * ({@code user_business_preferences.min_bid_price_eur}).
 *
 * <p>Jusqu'ici, seule l'app filtrait les offres trop basses dans la liste « À traiter » :
 * l'offre arrivait quand même au voyageur (notification comprise) et restait visible sur
 * tout autre écran. Désormais, une proposition de prix d'un expéditeur sous le minimum est
 * refusée dès sa création (422 {@code bid-below-min-price}).
 *
 * <p><b>Unité et devise du minimum.</b> La colonne garde son nom historique, mais l'écran
 * des réglages l'affiche et la saisit dans la devise active du voyageur
 * ({@code currency_code}). Le minimum est donc lu dans cette devise, puis converti dans la
 * devise du trajet au taux administré ({@link ExchangeRateService#convert}), comme les autres
 * comparaisons de prix entre devises. C'est un montant <b>net</b> par kilo : ce que touche
 * le voyageur, comme le {@code pricePerKg} qu'il voit sur ses demandes.
 *
 * <p><b>Équivalent par kilo.</b> Même calcul que {@code BidService#toResponse} : net total
 * divisé par le poids déclaré. Une offre sans poids (articles de grille ou hors grille
 * seulement, sorte de forfait) n'a pas d'équivalent par kilo dans le code : elle n'est pas
 * bloquée.
 *
 * <p><b>Ce que voit l'expéditeur.</b> Il ne reçoit jamais le net du voyageur : la propriété
 * {@code minPricePerKg} du ProblemDetail est le minimum ramené au <b>brut</b> par kilo
 * (commission incluse, arrondi au supérieur), c'est-à-dire le prix qu'il doit au moins
 * proposer, dans la devise du trajet ({@code currency}).
 *
 * <p>Un taux de change manquant ne bloque pas l'offre : la règle sert le confort du voyageur,
 * elle ne doit jamais empêcher un échange pour une raison technique.
 */
@Component
public class MinPricePerKgGuard {

    public static final String ERROR_CODE = "bid-below-min-price";

    private static final Logger log = LoggerFactory.getLogger(MinPricePerKgGuard.class);

    private final UserBusinessPrefsRepository repository;
    private final ExchangeRateService exchangeRateService;

    public MinPricePerKgGuard(UserBusinessPrefsRepository repository,
                              ExchangeRateService exchangeRateService) {
        this.repository = repository;
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * @param travelerId     voyageur dont le réglage s'applique
     * @param netTotal       montant net total proposé (part du voyageur), devise du trajet
     * @param weightKg       poids déclaré ; {@code null} ou nul : offre sans équivalent au kilo
     * @param tripCurrency   devise du trajet (celle du bid ou du fil)
     * @param commissionRate taux de commission figé sur l'offre, pour exprimer le minimum
     *                       en brut à l'expéditeur ; {@code null} : minimum exposé en net
     */
    public void assertAtLeastMinimum(UUID travelerId, BigDecimal netTotal, BigDecimal weightKg,
                                     String tripCurrency, BigDecimal commissionRate) {
        if (travelerId == null || netTotal == null || weightKg == null || weightKg.signum() <= 0) {
            return;
        }
        UserBusinessPrefsEntity prefs = repository.findById(travelerId).orElse(null);
        if (prefs == null || prefs.getMinBidPriceEur() <= 0) {
            return;
        }
        String currency = normalize(tripCurrency);
        BigDecimal minInTripCurrency;
        try {
            minInTripCurrency = exchangeRateService.convert(
                    BigDecimal.valueOf(prefs.getMinBidPriceEur()), prefs.getCurrencyCode(), currency);
        } catch (YadonyBusinessException e) {
            log.warn("Prix minimum du voyageur {} non applique : conversion {} -> {} impossible ({})",
                    travelerId, prefs.getCurrencyCode(), currency, e.getErrorCode());
            return;
        }
        BigDecimal netPerKg = netTotal.divide(weightKg, 4, RoundingMode.HALF_UP);
        if (netPerKg.compareTo(minInTripCurrency) >= 0) {
            return;
        }
        int decimals = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        BigDecimal shown = commissionRate != null && commissionRate.signum() >= 0
                ? minInTripCurrency.multiply(BigDecimal.ONE.add(commissionRate))
                : minInTripCurrency;
        shown = shown.setScale(decimals, RoundingMode.UP);
        throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE,
                "Bid Below Min Price",
                "Ce voyageur n'accepte pas moins de " + shown.toPlainString() + " " + currency + "/kg",
                Map.of("minPricePerKg", shown, "currency", currency));
    }

    private static String normalize(String currency) {
        return currency == null ? "EUR" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
