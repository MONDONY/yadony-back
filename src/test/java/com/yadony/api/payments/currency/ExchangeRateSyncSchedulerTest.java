package com.yadony.api.payments.currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Le scheduler délègue tout à {@link ExchangeRateSyncService} ; seul comportement
 * propre : la synchronisation de démarrage ne doit jamais faire échouer le boot.
 */
class ExchangeRateSyncSchedulerTest {

    @Test
    @DisplayName("au démarrage, la synchronisation est déclenchée")
    void startupTriggersSync() {
        ExchangeRateSyncService service = mock(ExchangeRateSyncService.class);
        new ExchangeRateSyncScheduler(service).syncOnStartup();
        verify(service).syncAll();
    }

    @Test
    @DisplayName("un échec au démarrage est avalé : l'application doit servir quand même")
    void startupFailureIsSwallowed() {
        ExchangeRateSyncService service = mock(ExchangeRateSyncService.class);
        doThrow(new IllegalStateException("BCE down")).when(service).syncAll();
        new ExchangeRateSyncScheduler(service).syncOnStartup();
        // Aucune exception propagée : le test échoue si syncOnStartup laisse filer.
    }

    @Test
    @DisplayName("le passage planifié délègue au service")
    void scheduledRunDelegates() {
        ExchangeRateSyncService service = mock(ExchangeRateSyncService.class);
        new ExchangeRateSyncScheduler(service).syncDailyRates();
        verify(service).syncAll();
    }
}
