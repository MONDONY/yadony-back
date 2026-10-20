package com.yadony.api.payments;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StripeOnboardingResumeTokensTest {

    private static final String KEY = "cle-de-test-suffisamment-longue";
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private final UUID userId = UUID.randomUUID();

    private StripeOnboardingResumeTokens at(Instant instant) {
        return new StripeOnboardingResumeTokens(KEY, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issuedToken_givesBackItsUser() {
        String token = at(NOW).issue(userId);

        assertThat(at(NOW).verify(token)).contains(userId);
        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    void token_expiresAfterFourteenDays() {
        String token = at(NOW).issue(userId);

        assertThat(at(NOW.plus(StripeOnboardingResumeTokens.VALIDITY).minusSeconds(1)).verify(token))
                .contains(userId);
        assertThat(at(NOW.plus(StripeOnboardingResumeTokens.VALIDITY)).verify(token)).isEmpty();
    }

    @Test
    void tamperedToken_isRejected() {
        String token = at(NOW).issue(userId);
        String otherPayload = at(NOW).issue(UUID.randomUUID()).split("\\.")[0];
        String forged = otherPayload + "." + token.split("\\.")[1];

        assertThat(at(NOW).verify(forged)).isEmpty();
        assertThat(at(NOW).verify(token.substring(0, token.length() - 2) + "AA")).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        String token = new StripeOnboardingResumeTokens("une-autre-cle", Clock.fixed(NOW, ZoneOffset.UTC))
                .issue(userId);

        assertThat(at(NOW).verify(token)).isEmpty();
    }

    @Test
    void garbage_isRejectedWithoutThrowing() {
        StripeOnboardingResumeTokens tokens = at(NOW);

        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify(" ")).isEmpty();
        assertThat(tokens.verify("pas-de-point")).isEmpty();
        assertThat(tokens.verify(".signature")).isEmpty();
        assertThat(tokens.verify("a.b.c")).isEmpty();
        assertThat(tokens.verify("@@@.###")).isEmpty();
    }

    @Test
    void wellSignedButMalformedPayload_isRejected() throws Exception {
        // Charge utile signée avec la bonne clé mais illisible : sans « : », puis UUID faux.
        java.lang.reflect.Method sign = StripeOnboardingResumeTokens.class.getDeclaredMethod("sign", String.class);
        sign.setAccessible(true);
        StripeOnboardingResumeTokens tokens = at(NOW);
        java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
        for (String payload : new String[]{"sans-deux-points", "pas-un-uuid:9999999999", userId + ":demain"}) {
            String token = enc.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + "." + enc.encodeToString((byte[]) sign.invoke(tokens, payload));
            assertThat(tokens.verify(token)).as(payload).isEmpty();
        }
    }

    @Test
    void springConstructor_usesTheSystemClock() {
        StripeOnboardingResumeTokens tokens = new StripeOnboardingResumeTokens(KEY);

        assertThat(tokens.verify(tokens.issue(userId))).contains(userId);
    }
}
