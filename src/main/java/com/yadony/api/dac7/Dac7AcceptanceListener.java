package com.yadony.api.dac7;

import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Collecte ciblée DAC7 (arbitrage R1) : après une acceptation de réservation (colis sur un trajet : carte, espèces ou
 * mobile money, {@link BidAcceptedEvent} ; demande de colis négociée, {@link PackageRequestAcceptedEvent}), invite
 * NON bloquante à compléter les informations fiscales ({@link Dac7ReminderService#requestAtAcceptance}).
 *
 * <ul>
 *   <li>Ne part que si la collecte ({@code YADONY_DAC7_ENABLED}) ET les messages DAC7
 *       ({@code YADONY_DAC7_REMINDERS_ENABLED}) sont allumés, comme {@link Dac7ReminderScheduler} (relecture I1) :
 *       allumer la seule collecte n'envoie aucun message. Éteint : rien n'est lu, écrit ni mis en file.</li>
 *   <li>Après validation de l'acceptation, sur le fil appelant, l'écouteur ne fait que lire deux drapeaux puis confie
 *       le travail à l'exécuteur dédié {@value Dac7ExecutorConfig#EXECUTOR} (relecture I3) : jamais le pool
 *       {@code @Async} partagé, jamais d'appel au prestataire ici. File pleine : invite abandonnée et journalisée.</li>
 *   <li>Aucune erreur ne remonte : l'acceptation n'en sait rien.</li>
 * </ul>
 */
@Component
public class Dac7AcceptanceListener {

    private static final Logger log = LoggerFactory.getLogger(Dac7AcceptanceListener.class);

    private final Dac7Switches switches;
    private final Dac7ReminderService reminders;
    private final Clock clock;
    private final Executor executor;

    /** Tests : interrupteurs dérivés des propriétés (collecte et messages, sans le back-office). */
    Dac7AcceptanceListener(Dac7Properties properties, Dac7ReminderProperties reminderProperties,
                           Dac7ReminderService reminders, Clock clock, Executor executor) {
        this(Dac7Switches.fixed(properties.enabledOrDefault(), reminderProperties.enabled()), reminders, clock,
                executor);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Dac7AcceptanceListener(Dac7Switches switches, Dac7ReminderService reminders, Clock clock,
                                  @Qualifier(Dac7ExecutorConfig.EXECUTOR) Executor executor) {
        this.switches = switches;
        this.reminders = reminders;
        this.clock = clock;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidAccepted(BidAcceptedEvent event) {
        prompt(event.getTravelerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPackageRequestAccepted(PackageRequestAcceptedEvent event) {
        prompt(event.travelerId());
    }

    private void prompt(UUID travelerId) {
        if (!switches.remindersEnabled() || travelerId == null) {
            return;
        }
        try {
            executor.execute(() -> request(travelerId));
        } catch (RuntimeException e) {
            // File pleine (RejectedExecutionException) ou exécuteur arrêté : l'acceptation n'en sait rien.
            log.warn("DAC7 : invite après acceptation abandonnée (voyageur {}) : {}", travelerId,
                    e.getClass().getSimpleName());
        }
    }

    private void request(UUID travelerId) {
        try {
            reminders.requestAtAcceptance(travelerId, Instant.now(clock));
        } catch (RuntimeException e) {
            log.warn("DAC7 : invite après acceptation non envoyée (voyageur {}) : {}", travelerId,
                    e.getClass().getSimpleName());
        }
    }
}
