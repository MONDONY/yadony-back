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

    /** Même compteur par IP (adresse vue par nginx), tous comptes confondus. */
    @Bean("topupCodeIpFailureCache")
    public Cache<String, AtomicInteger> topupCodeIpFailureCache() {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    /**
     * Compteur de toute la plateforme, et marqueur « alerte déjà levée » de la fenêtre. Cache à
     * part, de deux entrées : un afflux d'IP ne peut pas l'évincer.
     */
    @Bean("topupCodeGlobalFailureCache")
    public Cache<String, AtomicInteger> topupCodeGlobalFailureCache() {
        return Caffeine.newBuilder()
                .maximumSize(16)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }
}
