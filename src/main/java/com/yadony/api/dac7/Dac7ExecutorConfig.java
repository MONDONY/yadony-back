package com.yadony.api.dac7;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Exécuteur dédié à l'invite DAC7 après acceptation (relecture I3), volontairement étroit : jamais le pool
 * {@code @Async} partagé ({@code applicationTaskExecutor}, qui porte les notifications de réservation et de paiement).
 * Deux fils, file de 100 ; au-delà, la tâche est REFUSÉE ({@code AbortPolicy}) et l'écouteur l'abandonne en le
 * journalisant : l'invite repartira à une acceptation suivante ou par les rappels, jamais au détriment du reste.
 */
@Configuration
public class Dac7ExecutorConfig {

    public static final String EXECUTOR = "dac7Executor";

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor dac7Executor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("dac7-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
