package com.yadony.api.calls;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreamTokenServiceTest {

    static final String SECRET = "s3cr3t-de-test-assez-long-pour-hs256-0123456789";
    final Instant now = Instant.parse("2026-10-02T10:00:00Z");
    final StreamTokenService service = new StreamTokenService(
            new StreamProperties(true, "https://video.stream-io-api.com", "key", SECRET, "audio_call", 3),
            Clock.fixed(now, ZoneOffset.UTC));

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .clock(() -> java.util.Date.from(now)).build().parseSignedClaims(token).getPayload();
    }

    @Test
    void jetonServeurPorteServerTrue() {
        assertThat(parse(service.serverToken()).get("server", Boolean.class)).isTrue();
    }

    @Test
    void jetonUtilisateurExpireDans24h() {
        UUID userId = UUID.randomUUID();
        StreamTokenService.IssuedToken issued = service.userToken(userId);
        Claims claims = parse(issued.token());
        assertThat(claims.get("user_id", String.class)).isEqualTo(userId.toString());
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(24 * 3600));
        assertThat(claims.getExpiration().toInstant()).isEqualTo(issued.expiresAt());
    }
}
