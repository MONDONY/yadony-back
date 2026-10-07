package com.yadony.api.messaging;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anti-spam des photos de messagerie (FLUTTER-B4), sur le modèle d'{@code addressRateLimitCache} :
 * fenêtres fixes ouvertes au premier envoi (le compteur est muté sur place, l'entrée n'est
 * jamais réécrite, donc l'expiration court depuis sa création).
 */
@Configuration
public class MessagingMediaConfig {

    /** Par utilisateur : 10 photos par fenêtre de 10 minutes. */
    @Bean("messagingImageUserRateCache")
    public Cache<String, AtomicInteger> messagingImageUserRateCache() {
        return Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
    }

    /** Par utilisateur et par conversation : 50 photos par fenêtre de 24 heures. */
    @Bean("messagingImageConversationRateCache")
    public Cache<String, AtomicInteger> messagingImageConversationRateCache() {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(24, TimeUnit.HOURS)
                .build();
    }
}
