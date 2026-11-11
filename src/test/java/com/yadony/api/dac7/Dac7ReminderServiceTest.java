package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.dac7.events.Dac7TaxInfoRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Rappels DAC7 : demande, 14 jours, 30 jours, jamais de troisième, seulement SOLLICITER")
class Dac7ReminderServiceTest {

    private static final Instant NOW = Instant.parse("2027-01-10T11:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final Dac7SolicitationPolicy solicitation = mock(Dac7SolicitationPolicy.class);
    private final Dac7DueDiligenceService diligence = mock(Dac7DueDiligenceService.class);
    private final Dac7AdminReadModel reads = mock(Dac7AdminReadModel.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Dac7ReminderService service = new Dac7ReminderService(users, solicitation, diligence, reads,
            Dac7ReminderProperties.defaults(true), events);
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(solicitation.statusOf(user)).thenReturn(Dac7SolicitationStatus.SOLLICITER);
        when(reads.lastOutgoing(any())).thenReturn(Optional.empty());
    }

    private void requestedDaysAgo(long days, int reminders) {
        user.setTaxInfoRequestedAt(NOW.minus(Duration.ofDays(days)));
        user.setTaxReminderCount(reminders);
        when(reads.lastOutgoing(user.getId())).thenReturn(Optional.of(NOW.minus(Duration.ofDays(days))));
    }

    @Test
    @DisplayName("premier passage : demande initiale, date posée, registre REQUEST, notification après validation")
    void premiereDemande() {
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.SENT);
        assertThat(user.getTaxInfoRequestedAt()).isEqualTo(NOW);
        assertThat(user.getTaxReminderCount()).isZero();
        verify(diligence).record(user.getId(), Dac7DueDiligenceAction.REQUEST, Dac7DueDiligenceChannel.IN_APP, null,
                null);
        verify(events).publishEvent(new Dac7TaxInfoRequestedEvent(user.getId(), "REQUEST"));
    }

    @Test
    @DisplayName("rappel 1 à 14 jours de la demande, pas avant")
    void rappel1() {
        requestedDaysAgo(13, 0);
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.NOT_DUE);
        requestedDaysAgo(14, 0);
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.SENT);
        assertThat(user.getTaxReminderCount()).isEqualTo(1);
        verify(diligence).record(user.getId(), Dac7DueDiligenceAction.REMINDER_1, Dac7DueDiligenceChannel.IN_APP,
                null, null);
    }

    @Test
    @DisplayName("rappel 2 à 30 jours de la demande, pas avant")
    void rappel2() {
        requestedDaysAgo(29, 1);
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.NOT_DUE);
        requestedDaysAgo(30, 1);
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.SENT);
        assertThat(user.getTaxReminderCount()).isEqualTo(2);
        verify(events).publishEvent(new Dac7TaxInfoRequestedEvent(user.getId(), "REMINDER_2"));
    }

    @Test
    @DisplayName("rappel 2 jamais moins de 7 jours après le rappel 1, même après une pause du planificateur")
    void ecartMinimal() {
        // Demande il y a 35 jours, rappel 1 parti en retard il y a 3 jours (planificateur arrêté).
        user.setTaxInfoRequestedAt(NOW.minus(Duration.ofDays(35)));
        user.setTaxReminderCount(1);
        when(reads.lastOutgoing(user.getId())).thenReturn(Optional.of(NOW.minus(Duration.ofDays(3))));
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.NOT_DUE);
        when(reads.lastOutgoing(user.getId())).thenReturn(Optional.of(NOW.minus(Duration.ofDays(7))));
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.SENT);
        assertThat(user.getTaxReminderCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("jamais de troisième rappel, même un an plus tard, ni par l'équipe")
    void jamaisDeTroisieme() {
        requestedDaysAgo(365, 2);
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.EXHAUSTED);
        assertThat(service.remindByAdmin(user.getId(), UUID.randomUUID(), NOW))
                .isEqualTo(Dac7ReminderService.Outcome.EXHAUSTED);
        assertThat(user.getTaxReminderCount()).isEqualTo(2);
        verifyNoInteractions(diligence, events);
    }

    @Test
    @DisplayName("voyageur non SOLLICITER (déjà fourni, hors périmètre, jamais payé) : jamais touché")
    void nonSolliciter() {
        for (Dac7SolicitationStatus status : new Dac7SolicitationStatus[]{Dac7SolicitationStatus.DEJA_FOURNI,
                Dac7SolicitationStatus.NE_PAS_SOLLICITER}) {
            when(solicitation.statusOf(user)).thenReturn(status);
            assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.NOT_SOLICITABLE);
            assertThat(service.remindByAdmin(user.getId(), UUID.randomUUID(), NOW))
                    .isEqualTo(Dac7ReminderService.Outcome.NOT_SOLICITABLE);
        }
        assertThat(user.getTaxInfoRequestedAt()).isNull();
        verify(users, never()).save(any());
        verifyNoInteractions(diligence, events);
    }

    @Test
    @DisplayName("un seul message par jour : un message parti il y a moins de 24 heures bloque l'envoi automatique")
    void unParJour() {
        requestedDaysAgo(14, 0);
        when(reads.lastOutgoing(user.getId())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(5))));
        assertThat(service.remindIfDue(user.getId(), NOW)).isEqualTo(Dac7ReminderService.Outcome.TOO_SOON);
        verifyNoInteractions(diligence, events);
    }

    @Test
    @DisplayName("relance de l'équipe : démarche suivante sans attendre les délais, canal ADMIN, auteur au registre")
    void relanceManuelle() {
        UUID admin = UUID.randomUUID();
        requestedDaysAgo(8, 0);
        assertThat(service.remindByAdmin(user.getId(), admin, NOW)).isEqualTo(Dac7ReminderService.Outcome.SENT);
        assertThat(user.getTaxReminderCount()).isEqualTo(1);
        verify(diligence).record(eq(user.getId()), eq(Dac7DueDiligenceAction.REMINDER_1),
                eq(Dac7DueDiligenceChannel.ADMIN), eq(admin), isNull());
    }

    @Test
    @DisplayName("relance de l'équipe moins de 7 jours après le dernier message : refusée")
    void relanceTropTot() {
        requestedDaysAgo(6, 0);
        assertThat(service.remindByAdmin(user.getId(), UUID.randomUUID(), NOW))
                .isEqualTo(Dac7ReminderService.Outcome.TOO_SOON);
        verifyNoInteractions(diligence, events);
    }

    @Test
    @DisplayName("compte introuvable")
    void introuvable() {
        UUID other = UUID.randomUUID();
        when(users.findById(other)).thenReturn(Optional.empty());
        assertThat(service.remindIfDue(other, NOW)).isEqualTo(Dac7ReminderService.Outcome.NOT_FOUND);
        assertThat(service.remindByAdmin(other, UUID.randomUUID(), NOW))
                .isEqualTo(Dac7ReminderService.Outcome.NOT_FOUND);
    }
}
