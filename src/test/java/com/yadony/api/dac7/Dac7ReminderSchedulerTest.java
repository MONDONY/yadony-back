package com.yadony.api.dac7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Planificateur des rappels DAC7 : éteint par défaut, seulement les résidents déclarables déjà payés")
class Dac7ReminderSchedulerTest {

    private static final Instant NOON_PARIS = Instant.parse("2027-01-10T11:00:00Z");
    private static final Instant NIGHT_PARIS = Instant.parse("2027-01-10T02:00:00Z");

    private final Dac7ReminderService reminders = mock(Dac7ReminderService.class);
    private final Dac7AdminReadModel reads = mock(Dac7AdminReadModel.class);
    private final Dac7ReportReadModel reportReads = mock(Dac7ReportReadModel.class);
    private final Dac7ResidencyPolicy residency = new Dac7ResidencyPolicy(new Dac7Properties(true, null));

    private Dac7ReminderScheduler scheduler(boolean collection, boolean remindersOn, Instant now) {
        return new Dac7ReminderScheduler(reminders, reads, reportReads, residency,
                new Dac7Properties(collection, null), Dac7ReminderProperties.defaults(remindersOn),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("éteint (rappels ou collecte) : aucune lecture, aucun envoi")
    void eteint() {
        scheduler(true, false, NOON_PARIS).run();
        scheduler(false, true, NOON_PARIS).run();
        verifyNoInteractions(reads, reportReads, reminders);
    }

    @Test
    @DisplayName("résident français payé : traité ; ivoirien ou jamais payé : jamais touché ; nuit : rien")
    void filtre() {
        UUID french = UUID.randomUUID();
        UUID ivorian = UUID.randomUUID();
        UUID neverPaid = UUID.randomUUID();
        when(reads.reminderCandidates(any(), any())).thenReturn(List.of(
                new Dac7AdminReadModel.ReminderCandidate(french, "FR", null, null, null),
                new Dac7AdminReadModel.ReminderCandidate(ivorian, "CI", null, null, null),
                new Dac7AdminReadModel.ReminderCandidate(neverPaid, "FR", null, null, null)));
        when(reportReads.paidTravelers(List.of(french, neverPaid))).thenReturn(Set.of(french));
        when(reminders.remindIfDue(french, NOON_PARIS)).thenReturn(Dac7ReminderService.Outcome.SENT);

        scheduler(true, true, NOON_PARIS).run();

        verify(reminders).remindIfDue(french, NOON_PARIS);
        verify(reminders, never()).remindIfDue(eq(ivorian), any());
        verify(reminders, never()).remindIfDue(eq(neverPaid), any());

        scheduler(true, true, NIGHT_PARIS).run();
        verify(reminders, never()).remindIfDue(french, NIGHT_PARIS);
    }

    @Test
    @DisplayName("un échec sur un voyageur n'arrête pas les autres")
    void echecIsole() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(reads.reminderCandidates(any(), any())).thenReturn(List.of(
                new Dac7AdminReadModel.ReminderCandidate(a, "FR", null, null, null),
                new Dac7AdminReadModel.ReminderCandidate(b, "FR", null, null, null)));
        when(reportReads.paidTravelers(any())).thenReturn(Set.of(a, b));
        when(reminders.remindIfDue(a, NOON_PARIS)).thenThrow(new IllegalStateException("x"));

        scheduler(true, true, NOON_PARIS).run();

        verify(reminders).remindIfDue(b, NOON_PARIS);
    }
}
