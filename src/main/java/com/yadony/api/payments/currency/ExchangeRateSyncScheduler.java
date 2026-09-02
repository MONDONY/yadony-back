package com.yadony.api.payments.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateSyncScheduler.class);

    private final ExchangeRateSyncService syncService;

    public ExchangeRateSyncScheduler(ExchangeRateSyncService syncService) {
        this.syncService = syncService;
    }

    // zone explicite : le cron doit viser 07 h 00 UTC quel que soit le fuseau de la
    // JVM du conteneur — un TZ hérité décalerait silencieusement l'heure de passage.
    @Scheduled(cron = "${yadony.exchange-rates.sync-cron:0 0 7 * * *}", zone = "UTC")
    public void syncDailyRates() {
        syncService.syncAll();
    }

    /**
     * Synchronisation au démarrage : après un déploiement ou un long arrêt, les taux
     * en base peuvent dater ; attendre le prochain passage du cron laisse jusqu'à
     * 24 h d'écart. Best-effort : un échec s'alerte via le service, jamais en
     * empêchant l'application de servir.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            syncService.syncAll();
        } catch (RuntimeException e) {
            log.warn("Synchronisation BCE au démarrage en échec : {}", e.getMessage());
        }
    }
}
