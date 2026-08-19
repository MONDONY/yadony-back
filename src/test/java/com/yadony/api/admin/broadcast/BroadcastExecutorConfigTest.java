package com.yadony.api.admin.broadcast;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preuve du bornage, pas hypothèse : un broadcast massif sur cette base doit rester une
 * traînée de fond — un seul thread, une file courte, et un rejet qui ralentit visiblement
 * l'appelant plutôt qu'une file qui gonfle en silence.
 */
class BroadcastExecutorConfigTest {

    @Test
    void executorIsBoundedToASingleBackgroundThreadWithAShortCallerRunsQueue() {
        ThreadPoolTaskExecutor executor = new BroadcastExecutorConfig().broadcastExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(20);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
}
