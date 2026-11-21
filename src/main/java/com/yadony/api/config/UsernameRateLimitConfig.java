package com.yadony.api.config;

import com.yadony.api.addressbook.invitation.LookupRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Les deux plafonds horaires par utilisateur sur l'identifiant public. Seaux indépendants : épuiser
 * la disponibilité ne consomme pas les recherches, et inversement.
 */
@Configuration
public class UsernameRateLimitConfig {

    /** Plafond des vérifications de disponibilité ({@code GET /auth/me/username/availability}). */
    public static final int AVAILABILITY_MAX_PER_HOUR = 120;

    /** Recherches et envois par identifiant : 30 par heure, seau partagé. */
    @Bean
    public LookupRateLimiter lookupRateLimiter() {
        return new LookupRateLimiter(Clock.systemUTC(), LookupRateLimiter.MAX_PER_HOUR);
    }

    @Bean
    public LookupRateLimiter usernameAvailabilityRateLimiter() {
        return new LookupRateLimiter(Clock.systemUTC(), AVAILABILITY_MAX_PER_HOUR);
    }
}
