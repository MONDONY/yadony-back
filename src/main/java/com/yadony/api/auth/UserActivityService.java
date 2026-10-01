package com.yadony.api.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.auth.events.UserSeenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dernière connexion affichée sur le profil public (FLUTTER-4H). Une écriture au plus
 * par quart d'heure et par utilisateur : l'app relit son profil à chaque reprise, la
 * base n'a pas à le suivre à la seconde.
 */
@Service
public class UserActivityService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);
    static final Duration WRITE_INTERVAL = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final Clock clock;
    private final Cache<UUID, Boolean> recentlyWritten = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(WRITE_INTERVAL)
            .build();

    @org.springframework.beans.factory.annotation.Autowired
    public UserActivityService(UserRepository userRepository) {
        this(userRepository, Clock.systemUTC());
    }

    UserActivityService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** Hors de la transaction en lecture de l'appelant ; un échec n'empêche jamais la connexion. */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserSeen(UserSeenEvent event) {
        if (recentlyWritten.getIfPresent(event.userId()) != null) {
            return;
        }
        try {
            userRepository.touchLastSeen(event.userId(), LocalDateTime.now(clock));
            recentlyWritten.put(event.userId(), Boolean.TRUE);
        } catch (RuntimeException e) {
            log.warn("Dernière connexion non enregistrée pour {} : {}", event.userId(), e.getMessage());
        }
    }
}
