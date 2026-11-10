package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSuggestionUnsubscribeTokensTest {

    private static final String KEY = "une-cle-de-test-suffisamment-longue";
    private final Instant now = Instant.parse("2026-11-07T10:00:00Z");
    private final SearchSuggestionUnsubscribeTokens tokens =
            new SearchSuggestionUnsubscribeTokens(KEY, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    @Test
    void issuedToken_verifiesBackToTheUser() {
        assertThat(tokens.verify(tokens.issue(userId))).contains(userId);
    }

    @Test
    void tokenStaysValidForAYear_thenExpires() {
        String token = tokens.issue(userId);
        Instant almost = now.plus(SearchSuggestionUnsubscribeTokens.VALIDITY).minus(Duration.ofMinutes(1));
        Instant after = now.plus(SearchSuggestionUnsubscribeTokens.VALIDITY);

        assertThat(new SearchSuggestionUnsubscribeTokens(KEY, Clock.fixed(almost, ZoneOffset.UTC)).verify(token))
                .contains(userId);
        assertThat(new SearchSuggestionUnsubscribeTokens(KEY, Clock.fixed(after, ZoneOffset.UTC)).verify(token))
                .isEmpty();
    }

    @Test
    void otherKey_forgedPayload_orGarbage_areRejected() {
        String token = tokens.issue(userId);
        assertThat(new SearchSuggestionUnsubscribeTokens("autre-cle", Clock.fixed(now, ZoneOffset.UTC)).verify(token))
                .isEmpty();

        String signature = token.substring(token.indexOf('.') + 1);
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (UUID.randomUUID() + ":" + now.plusSeconds(3600).getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        assertThat(tokens.verify(forged + "." + signature)).isEmpty();

        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify(" ")).isEmpty();
        assertThat(tokens.verify("sans-point")).isEmpty();
        assertThat(tokens.verify("a.b.c")).isEmpty();
        assertThat(tokens.verify("%%%.###")).isEmpty();
    }

    @Test
    void stripeResumeToken_isNotAnUnsubscribeToken() {
        String stripe = new com.yadony.api.payments.StripeOnboardingResumeTokens(KEY).issue(userId);
        assertThat(tokens.verify(stripe)).isEmpty();
    }

    @Test
    void payloadSwappedForAnotherOne_isRejected() {
        // Charge remplacée (sans « : »), signature d'origine : elle ne correspond plus.
        String token = tokens.issue(userId);
        String payloadWithoutColon = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(tokens.verify(payloadWithoutColon + token.substring(token.indexOf('.')))).isEmpty();
    }

    @Test
    void tokenIssuedBeforeTheSharedImplementation_staysValid() {
        // Valeur produite par l'ancienne classe (avant SignedUnsubscribeTokens) : les liens déjà
        // envoyés par e-mail doivent continuer à fonctionner.
        String frozen = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwOjE4MjU1ODE2MDA"
                + ".aB0NMM3XAbP0HawsKCy17ooMUeNryh5_rQULNnaGTTY";
        assertThat(tokens.verify(frozen))
                .contains(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        assertThat(tokens.issue(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))).isEqualTo(frozen);
    }
}
