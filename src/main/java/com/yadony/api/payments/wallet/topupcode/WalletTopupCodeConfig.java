package com.yadony.api.payments.wallet.topupcode;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WalletTopupCodeConfig {

    /**
     * Échecs « code inconnu » par utilisateur. Fenêtre fixe d'une heure à partir du premier
     * échec : incrémenter l'AtomicInteger ne réécrit pas l'entrée, donc ne prolonge pas l'expiration.
     */
    @Bean("topupCodeFailureCache")
    public Cache<UUID, AtomicInteger> topupCodeFailureCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }
}
