package com.yadony.api.admin.broadcast;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executeur dedie au broadcast, volontairement etroit.
 *
 * <p>⚠️ Spring Boot auto-configure deja un {@code applicationTaskExecutor} (aliase
 * {@code taskExecutor}) que tous les {@code @Async} sans qualifieur utilisent — dont les
 * listeners de {@code NotificationDispatcher}. Ajouter ce second executeur ne les
 * deplace PAS : Spring resout le defaut par le nom {@code taskExecutor}. En contrepartie,
 * tout point d'entree de broadcast DOIT porter le qualifieur explicite
 * {@code @Async("broadcastExecutor")}, sans quoi un envoi de masse partagerait la file des
 * notifications transactionnelles et les retarderait.
 *
 * <p>Un seul thread : un broadcast doit rester une trainee de fond, jamais une rafale qui
 * concurrence le trafic applicatif. File courte + {@code CallerRunsPolicy} : au-dela de
 * 20 broadcasts en attente, l'appelant execute lui-meme — la requete admin ralentit,
 * ce qui est un signal visible, plutot qu'une file qui gonfle en silence.
 */
@Configuration
public class BroadcastExecutorConfig {

    @Bean("broadcastExecutor")
    public ThreadPoolTaskExecutor broadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("broadcast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
