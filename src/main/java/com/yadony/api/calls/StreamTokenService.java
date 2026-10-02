package com.yadony.api.calls;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Jetons Stream signés HS256 avec le secret API. */
@Service
public class StreamTokenService {

    static final Duration USER_TOKEN_TTL = Duration.ofHours(24);

    public record IssuedToken(String token, Instant expiresAt) {}

    private final StreamProperties properties;
    private final Clock clock;

    @Autowired
    public StreamTokenService(StreamProperties properties) {
        this(properties, Clock.systemUTC());
    }

    StreamTokenService(StreamProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String serverToken() {
        Instant now = clock.instant();
        // iat reculé de 5 s : Stream refuse un jeton « émis dans le futur » au moindre décalage d'horloge.
        return Jwts.builder().claim("server", true).issuedAt(Date.from(now.minusSeconds(5)))
                .signWith(key(), Jwts.SIG.HS256).compact();
    }

    public IssuedToken userToken(UUID userId) {
        Instant now = clock.instant();
        Instant exp = now.plus(USER_TOKEN_TTL);
        String token = Jwts.builder().claim("user_id", userId.toString())
                .issuedAt(Date.from(now.minusSeconds(5))).expiration(Date.from(exp))
                .signWith(key(), Jwts.SIG.HS256).compact();
        return new IssuedToken(token, exp);
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(properties.apiSecret().getBytes(StandardCharsets.UTF_8));
    }
}
