package com.yadony.api.payments.currency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclenche la synchronisation BCE quotidienne ({@link ExchangeRateSyncService}).
 *
 * <p>07 h 00 UTC par défaut : la BCE publie ses taux de référence vers 16 h CET la
 * veille — à cette heure le flux du dernier jour ouvré est toujours disponible, et le
 * week-end la synchronisation relit le taux du vendredi (idempotente, aucune écriture).
 *
 * <p>Désactivable par propriété ({@code yadony.exchange-rates.sync-enabled=false}),
 * et désactivée dans {@code application-test.yml} : un test d'intégration n'a pas à
 * dépendre d'un flux externe ni d'une horloge.
 */
@Component
@ConditionalOnProperty(value = "yadony.exchange-rates.sync-enabled",
        havingValue = "true", matchIfMissing = true)
public class ExchangeRateSyncScheduler {

    private final ExchangeRateSyncService syncService;

    public ExchangeRateSyncScheduler(ExchangeRateSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${yadony.exchange-rates.sync-cron:0 0 7 * * *}")
    public void syncDailyRates() {
        syncService.syncAll();
    }
}
