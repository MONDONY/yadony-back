package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SignedUnsubscribeTokensTest {

    private static final String KEY = "une-cle-de-test-suffisamment-longue";
    private static final UUID USER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private final Instant now = Instant.parse("2026-11-07T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private SignedUnsubscribeTokens suggestion() {
        return new SignedUnsubscribeTokens("search-suggestion-unsubscribe", KEY, clock);
    }

    private SignedUnsubscribeTokens kyc() {
        return new SignedUnsubscribeTokens("kyc-reminder-unsubscribe", KEY, clock);
    }

    @Test
    void ancienJetonSuggestion_resteValide() {
        // Valeur produite par l'ancienne classe, horloge fixe : les liens déjà envoyés restent valables.
        String frozen = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwOjE4MjU1ODE2MDA"
                + ".aB0NMM3XAbP0HawsKCy17ooMUeNryh5_rQULNnaGTTY";
        assertThat(suggestion().verify(frozen)).contains(USER);
        assertThat(suggestion().issue(USER)).isEqualTo(frozen);
    }

    @Test
    void jetonKyc_refuseCommeJetonSuggestion_etInversement() {
        assertThat(suggestion().verify(kyc().issue(USER))).isEmpty();
        assertThat(kyc().verify(suggestion().issue(USER))).isEmpty();
        assertThat(kyc().verify(kyc().issue(USER))).contains(USER);
    }

    @Test
    void jetonExpireApresUnAn() {
        String token = kyc().issue(USER);
        Clock almost = Clock.fixed(now.plus(Duration.ofDays(365)).minusSeconds(60), ZoneOffset.UTC);
        Clock after = Clock.fixed(now.plus(Duration.ofDays(365)), ZoneOffset.UTC);
        assertThat(new SignedUnsubscribeTokens("kyc-reminder-unsubscribe", KEY, almost).verify(token)).contains(USER);
        assertThat(new SignedUnsubscribeTokens("kyc-reminder-unsubscribe", KEY, after).verify(token)).isEmpty();
    }
}
