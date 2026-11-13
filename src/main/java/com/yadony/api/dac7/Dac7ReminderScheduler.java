package com.yadony.api.dac7;

import com.yadony.api.common.UserZones;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rappels DAC7 (lot 3b), ÉTEINTS par défaut ({@code YADONY_DAC7_REMINDERS_ENABLED}) et seulement si la collecte est
 * allumée ({@code YADONY_DAC7_ENABLED}). Toutes les heures, dans la fenêtre 10 h-20 h du pays du voyageur :
 * <ol>
 *   <li>candidats en SQL : comptes actifs sans NIF, moins de deux rappels, démarche due ;</li>
 *   <li>résidence déclarable (règle unique {@link Dac7ResidencyPolicy}) ;</li>
 *   <li>déjà payés, en une requête groupée par paquet ({@link Dac7ReportReadModel#paidTravelers}) ;</li>
 *   <li>pour chacun, {@link Dac7ReminderService#remindIfDue} dans sa propre transaction, qui revérifie tout.</li>
 * </ol>
 * Un échec sur un voyageur n'arrête pas les autres. Aucun NIF ni valeur au journal : des compteurs et des identifiants.
 */
@Component
public class Dac7ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(Dac7ReminderScheduler.class);

    private final Dac7ReminderService reminders;
    private final Dac7AdminReadModel reads;
    private final Dac7ReportReadModel reportReads;
    private final Dac7ResidencyPolicy residency;
    private final Dac7Switches switches;
    private final Dac7ReminderProperties properties;
    private final Clock clock;

    @Autowired
    public Dac7ReminderScheduler(Dac7ReminderService reminders, Dac7AdminReadModel reads,
                                 Dac7ReportReadModel reportReads, Dac7ResidencyPolicy residency, Dac7Switches switches,
                                 Dac7ReminderProperties properties) {
        this(reminders, reads, reportReads, residency, switches, properties, Clock.systemUTC());
    }

    /** Tests : interrupteurs dérivés des propriétés (collecte et messages, sans le back-office). */
    Dac7ReminderScheduler(Dac7ReminderService reminders, Dac7AdminReadModel reads, Dac7ReportReadModel reportReads,
                          Dac7ResidencyPolicy residency, Dac7Properties dac7, Dac7ReminderProperties properties,
                          Clock clock) {
        this(reminders, reads, reportReads, residency,
                Dac7Switches.fixed(dac7.enabledOrDefault(), properties.enabled()), properties, clock);
    }

    Dac7ReminderScheduler(Dac7ReminderService reminders, Dac7AdminReadModel reads, Dac7ReportReadModel reportReads,
                          Dac7ResidencyPolicy residency, Dac7Switches switches, Dac7ReminderProperties properties,
                          Clock clock) {
        this.reminders = reminders;
        this.reads = reads;
        this.reportReads = reportReads;
        this.residency = residency;
        this.switches = switches;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${yadony.dac7.reminders.cron:0 15 * * * *}", zone = "UTC")
    public void run() {
        if (!switches.remindersEnabled()) {
            return;
        }
        Instant now = clock.instant();
        List<Dac7AdminReadModel.ReminderCandidate> candidates = reads.reminderCandidates(
                now.minus(properties.firstReminderDelay()), now.minus(properties.secondReminderDelay()));
        List<Dac7AdminReadModel.ReminderCandidate> declarable = candidates.stream()
                .filter(c -> residency.statusOf(Dac7ResidencyPolicy.residenceCountryOf(c.frozenResidenceCountry(),
                        c.country(), status(c.travelerStatus()), c.businessCountry()))
                        == Dac7ResidencyStatus.DECLARABLE)
                .toList();
        Set<UUID> paid = reportReads.paidTravelers(declarable.stream()
                .map(Dac7AdminReadModel.ReminderCandidate::userId).toList());
        int sent = 0;
        int failed = 0;
        for (Dac7AdminReadModel.ReminderCandidate candidate : declarable) {
            if (!paid.contains(candidate.userId()) || !UserZones.isWithinWindow(candidate.country(), now,
                    properties.windowStartHour(), properties.windowEndHour())) {
                continue;
            }
            try {
                if (reminders.remindIfDue(candidate.userId(), now) == Dac7ReminderService.Outcome.SENT) {
                    sent++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Rappel DAC7 non envoyé (utilisateur {}) : {}", candidate.userId(),
                        e.getClass().getSimpleName());
            }
        }
        if (sent > 0 || failed > 0) {
            log.info("Rappels DAC7 : {} candidats, {} envoyés, {} en échec", declarable.size(), sent, failed);
        }
    }

    private static TravelerStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TravelerStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
