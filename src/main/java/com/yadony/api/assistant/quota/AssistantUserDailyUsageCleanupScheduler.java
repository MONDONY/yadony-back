package com.yadony.api.assistant.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Chaque nuit : supprime physiquement les compteurs de questions de plus de 30 jours. Seul le jour courant
 * sert au quota ; sans purge, la table grossit d'une ligne par utilisateur actif et par jour. Table
 * technique (pas une entité métier) : pas de soft delete. Idempotent, sans risque à rejouer.
 */
@Component
public class AssistantUserDailyUsageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AssistantUserDailyUsageCleanupScheduler.class);
    static final int RETENTION_DAYS = 30;

    private final AssistantUserDailyUsageRepository repository;
    private final Clock clock;

    @Autowired
    public AssistantUserDailyUsageCleanupScheduler(AssistantUserDailyUsageRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AssistantUserDailyUsageCleanupScheduler(AssistantUserDailyUsageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void purgeOldDays() {
        LocalDate before = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(RETENTION_DAYS);
        int deleted = repository.deleteOlderThan(before);
        if (deleted > 0) {
            log.info("Assistant : {} compteurs de questions antérieurs au {} purgés", deleted, before);
        }
    }
}
