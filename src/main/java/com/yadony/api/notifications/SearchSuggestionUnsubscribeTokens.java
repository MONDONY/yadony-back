package com.yadony.api.notifications;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Jeton du lien « Ne plus recevoir ces suggestions » des e-mails de suggestion de trajets.
 * Délègue à {@link SignedUnsubscribeTokens} avec la finalité d'origine, pour que les liens déjà
 * envoyés restent valables.
 */
@Component
public class SearchSuggestionUnsubscribeTokens {

    static final String PURPOSE = "search-suggestion-unsubscribe";
    static final java.time.Duration VALIDITY = SignedUnsubscribeTokens.VALIDITY;

    private final SignedUnsubscribeTokens delegate;

    @Autowired
    public SearchSuggestionUnsubscribeTokens(@Value("${app.encryption.key}") String encryptionKey) {
        this(encryptionKey, Clock.systemUTC());
    }

    SearchSuggestionUnsubscribeTokens(String encryptionKey, Clock clock) {
        this.delegate = new SignedUnsubscribeTokens(PURPOSE, encryptionKey, clock);
    }

    public String issue(UUID userId) {
        return delegate.issue(userId);
    }

    /** Utilisateur du jeton, ou vide si le jeton est absent, falsifié ou expiré. */
    public Optional<UUID> verify(String token) {
        return delegate.verify(token);
    }
}
