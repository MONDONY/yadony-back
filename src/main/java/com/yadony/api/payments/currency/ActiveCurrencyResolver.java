package com.yadony.api.payments.currency;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Single source of truth for "which currency is this user working in right now".
 *
 * <p>The currency is snapshotted onto announcements, package requests, bids and
 * negotiation threads at creation time and never re-derived afterwards. Resolution
 * therefore only happens on those creation paths and on the search filters that must
 * scope results to the viewer's currency.
 *
 * <p>Every caller must go through this component: the fallback policy has to stay in
 * one place, and copying the repository lookup around means five feature packages end
 * up injecting {@code UserRepository} directly.
 */
@Component
public class ActiveCurrencyResolver {

    public static final String DEFAULT_CURRENCY = "EUR";

    private final UserRepository userRepository;
    private final UserBusinessPrefsRepository userBusinessPrefsRepository;

    public ActiveCurrencyResolver(UserRepository userRepository,
                                   UserBusinessPrefsRepository userBusinessPrefsRepository) {
        this.userRepository = userRepository;
        this.userBusinessPrefsRepository = userBusinessPrefsRepository;
    }

    /**
     * Devise active de l'utilisateur : celle choisie dans son portefeuille
     * ({@code user_business_preferences.currency_code}), gardee par
     * {@code CurrencyLockService} et modifiable tant que le solde est vide.
     *
     * <p>Le pays ne sert plus qu'a fournir une valeur initiale, et seulement tant
     * qu'aucune ligne de portefeuille n'existe encore pour cet utilisateur (le cas
     * d'un compte tout juste inscrit) : une fois le portefeuille configure, la devise
     * n'est plus jamais re-derivee du pays, meme si celui-ci change ensuite.
     */
    public String resolve(UUID userId) {
        if (userId == null) {
            return DEFAULT_CURRENCY;
        }
        java.util.Optional<String> walletCurrency = userBusinessPrefsRepository.findById(userId)
                .map(UserBusinessPrefsEntity::getCurrencyCode);
        if (walletCurrency.isPresent()) {
            return walletCurrency.get().toUpperCase(Locale.ROOT);
        }
        SupportedCurrency currency = userRepository.findById(userId)
                .map(UserEntity::getCountry)
                .map(CountryCatalog::currencyOf)
                .orElse(null);
        return currency != null
                ? currency.code().toUpperCase(Locale.ROOT)
                : DEFAULT_CURRENCY;
    }
}
