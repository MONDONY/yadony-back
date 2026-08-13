package com.yadony.api.payments.currency;

import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.springframework.stereotype.Component;

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
 * up injecting {@code UserBusinessPrefsRepository} directly.
 */
@Component
public class ActiveCurrencyResolver {

    public static final String DEFAULT_CURRENCY = "EUR";

    private final UserBusinessPrefsRepository userBusinessPrefsRepository;

    public ActiveCurrencyResolver(UserBusinessPrefsRepository userBusinessPrefsRepository) {
        this.userBusinessPrefsRepository = userBusinessPrefsRepository;
    }

    /** Returns the user's active currency code, or {@link #DEFAULT_CURRENCY} if unknown. */
    public String resolve(UUID userId) {
        if (userId == null) {
            return DEFAULT_CURRENCY;
        }
        return userBusinessPrefsRepository.findById(userId)
                .map(UserBusinessPrefsEntity::getCurrencyCode)
                .orElse(DEFAULT_CURRENCY);
    }
}
