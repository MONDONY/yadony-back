package com.yadony.api.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Jeton du lien « Ne plus recevoir ces rappels » des relances de vérification d'identité. */
@Component
public class KycReminderUnsubscribeTokens {

    static final String PURPOSE = "kyc-reminder-unsubscribe";

    private final SignedUnsubscribeTokens delegate;

    public KycReminderUnsubscribeTokens(@Value("${app.encryption.key}") String encryptionKey) {
        this.delegate = new SignedUnsubscribeTokens(PURPOSE, encryptionKey, Clock.systemUTC());
    }

    public String issue(UUID userId) {
        return delegate.issue(userId);
    }

    public Optional<UUID> verify(String token) {
        return delegate.verify(token);
    }
}
