package com.yadony.api.dac7;

import com.yadony.api.matching.events.BidAcceptedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;

/**
 * Collecte ciblée DAC7 (arbitrage R1) : après une acceptation de réservation (carte, espèces ou mobile money), invite
 * NON bloquante à compléter les informations fiscales ({@link Dac7ReminderService#requestAtAcceptance}). Après
 * validation de l'acceptation et sur un autre fil : une panne de DAC7 ou du prestataire de vérification ne retarde ni
 * n'annule jamais l'acceptation, et ne remonte jamais au voyageur (journal de la classe d'erreur seulement).
 *
 * <p>DAC7 éteint ({@code yadony.dac7.enabled}, défaut) : rien n'est lu ni écrit, comportement inchangé.
 */
@Component
public class Dac7AcceptanceListener {

    private static final Logger log = LoggerFactory.getLogger(Dac7AcceptanceListener.class);

    private final Dac7Properties properties;
    private final Dac7ReminderService reminders;
    private final Clock clock;

    public Dac7AcceptanceListener(Dac7Properties properties, Dac7ReminderService reminders, Clock clock) {
        this.properties = properties;
        this.reminders = reminders;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidAccepted(BidAcceptedEvent event) {
        if (!properties.enabledOrDefault() || event.getTravelerId() == null) {
            return;
        }
        try {
            reminders.requestAtAcceptance(event.getTravelerId(), Instant.now(clock));
        } catch (RuntimeException e) {
            log.warn("DAC7 : invite après acceptation non envoyée (voyageur {}) : {}", event.getTravelerId(),
                    e.getClass().getSimpleName());
        }
    }
}
