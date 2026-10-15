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
     * Tentatives « code inconnu » par utilisateur. Chaque essai réserve une unité AVANT la
     * recherche du code (libérée si le code existe) : l'entrée naît donc à la première tentative
     * de la fenêtre, pas au premier échec. Fenêtre fixe d'une heure à partir de cette création :
     * incrémenter ou décrémenter l'AtomicInteger ne réécrit pas l'entrée, donc ne prolonge pas
     * l'expiration. Au-delà de 5 unités réservées dans la fenêtre : 429.
     */
    @Bean("topupCodeFailureCache")
    public Cache<UUID, AtomicInteger> topupCodeFailureCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }
}
