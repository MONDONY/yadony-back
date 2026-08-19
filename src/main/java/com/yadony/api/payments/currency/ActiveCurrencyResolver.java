package com.yadony.api.payments.currency;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
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

    public ActiveCurrencyResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Devise active de l'utilisateur, derivee de son pays. Le repli euro couvre
     * deux cas legitimes : l'utilisateur n'a pas encore renseigne son pays, ou son
     * pays a disparu du catalogue.
     */
    public String resolve(UUID userId) {
        if (userId == null) {
            return DEFAULT_CURRENCY;
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
