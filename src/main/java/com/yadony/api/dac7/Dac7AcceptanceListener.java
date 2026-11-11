package com.yadony.api.dac7;

import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;

/**
 * Collecte ciblée DAC7 (arbitrage R1) : après une acceptation de réservation (colis sur un trajet : carte, espèces ou
 * mobile money, {@link BidAcceptedEvent} ; demande de colis négociée, {@link PackageRequestAcceptedEvent}), invite
 * NON bloquante à compléter les informations fiscales ({@link Dac7ReminderService#requestAtAcceptance}). Après
 * validation de l'acceptation et sur un autre fil : une panne de DAC7 ou du prestataire de vérification ne retarde ni
 * n'annule jamais l'acceptation, et ne remonte jamais au voyageur (journal de la classe d'erreur seulement).
 *
 * <p>DAC7 éteint ({@code yadony.dac7.enabled}, défaut) : rien n'est lu ni écrit, comportement inchangé.
 * {@code yadony.dac7.acceptance-prompt-enabled} (vrai par défaut, donc gouverné par {@code yadony.dac7.enabled}) ne
 * sert qu'à l'éteindre seul : le profil de test le fait, pour que les autres tests d'acceptation ne reçoivent pas une
 * notification de plus sur un fil asynchrone.
 */
@Component
public class Dac7AcceptanceListener {

    private static final Logger log = LoggerFactory.getLogger(Dac7AcceptanceListener.class);

    private final Dac7Properties properties;
    private final Dac7ReminderService reminders;
    private final Clock clock;
    private final boolean promptEnabled;

    public Dac7AcceptanceListener(Dac7Properties properties, Dac7ReminderService reminders, Clock clock,
                                  @Value("${yadony.dac7.acceptance-prompt-enabled:true}") boolean promptEnabled) {
        this.properties = properties;
        this.reminders = reminders;
        this.clock = clock;
        this.promptEnabled = promptEnabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidAccepted(BidAcceptedEvent event) {
        prompt(event.getTravelerId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPackageRequestAccepted(PackageRequestAcceptedEvent event) {
        prompt(event.travelerId());
    }

    private void prompt(java.util.UUID travelerId) {
        if (!properties.enabledOrDefault() || !promptEnabled || travelerId == null) {
            return;
        }
        try {
            reminders.requestAtAcceptance(travelerId, Instant.now(clock));
        } catch (RuntimeException e) {
            log.warn("DAC7 : invite après acceptation non envoyée (voyageur {}) : {}", travelerId,
                    e.getClass().getSimpleName());
        }
    }
}
