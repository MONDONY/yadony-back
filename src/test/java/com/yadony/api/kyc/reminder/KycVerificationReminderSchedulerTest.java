package com.yadony.api.kyc.reminder;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycVerificationReminderSchedulerTest {

    /** 11 h à Bamako, Douala (UTC+1 : 12 h), Paris (UTC+1 en novembre : 12 h) : dans la fenêtre 10 h-20 h. */
    private static final Instant MIDI_UTC = Instant.parse("2026-11-10T11:00:00Z");

    @Mock KycReminderRepository repository;
    @Mock UserRepository userRepository;
    @Mock NotificationDispatcher dispatcher;
    @Mock AuditService auditService;
    final AtomicInteger transactions = new AtomicInteger();
    KycVerificationReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = scheduler(MIDI_UTC, props(true, List.of("IMAGE_TOO_BLURRY")));
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private KycVerificationReminderScheduler scheduler(Instant now, KycReminderProperties props) {
        TransactionOperations countingTx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                transactions.incrementAndGet();
                return TransactionOperations.withoutTransaction().execute(action);
            }
        };
        return new KycVerificationReminderScheduler(repository, userRepository, dispatcher, auditService, countingTx,
                props, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static KycReminderProperties props(boolean enabled, List<String> codes) {
        return new KycReminderProperties(enabled, Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7),
                Duration.ofDays(10), 10, 20, codes);
    }

    private UserEntity user(String country, int count) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setCountry(country);
        u.setKycReminderCount(count);
        lenient().when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        lenient().when(repository.isStillCandidate(eq(u.getId()), any(), any())).thenReturn(1);
        lenient().when(repository.reminderCase(u.getId())).thenReturn("incomplete");
        return u;
    }

    private void candidates(UserEntity... users) {
        when(repository.findCandidates(any(), any()))
                .thenReturn(java.util.Arrays.stream(users).map(UserEntity::getId).toList());
    }

    @Test
    void desactive_neFaitRien() {
        scheduler = scheduler(MIDI_UTC, props(false, List.of()));
        scheduler.remind();
        verifyNoInteractions(repository, userRepository, dispatcher, auditService);
    }

    @Test
    void candidat_envoieAvecDrapeauEtAudite() {
        UserEntity u = user("ML", 0);
        candidates(u);

        scheduler.remind();

        assertThat(transactions.get()).isEqualTo(1);
        assertThat(u.getKycReminderCount()).isEqualTo(1);
        assertThat(u.getKycReminderLastAt()).isEqualTo(MIDI_UTC);
        verify(userRepository).save(u);
        verify(dispatcher).notifyUser(u.getId(), "Votre vérification vous attend",
                "Terminez-la en quelques minutes pour publier et recevoir vos paiements. Vos documents restent chez "
                        + "Didit, Yadony n'en garde aucune copie.",
                Map.of("type", "KYC_ACTION_REQUIRED", "reminder", "true", "attempt", "1", "case", "incomplete"));
        verify(auditService).log("USER", u.getId(), "KYC_VERIFICATION_REMINDER_SENT", u.getId(),
                Map.of("attempt", 1, "case", "incomplete"));
    }

    /**
     * Le re-contrôle SQL (isStillCandidate) rejoue aussi les délais : une nouvelle session ouverte entre la
     * sélection et la réservation remet l'ancre à maintenant, donc le compte n'est plus dû (0). Le SQL est
     * prouvé par KycReminderRepositoryPostgresIT ; ici on vérifie que le planificateur obéit à ce 0.
     */
    @Test
    void nouvelleSessionEntreSelectionEtEnvoi_rienNePart() {
        UserEntity u = user("ML", 0);
        candidates(u);
        when(repository.isStillCandidate(eq(u.getId()), any(), any())).thenReturn(0);

        scheduler.remind();

        assertThat(u.getKycReminderCount()).isZero();
        assertThat(u.getKycReminderLastAt()).isNull();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(dispatcher, auditService);
    }

    @Test
    void recontrole_recoitLesMemesEcheancesQueLaSelection() {
        UserEntity u = user("ML", 0);
        candidates(u);

        scheduler.remind();

        var due = new KycReminderRepository.Due(MIDI_UTC.minus(Duration.ofDays(1)), MIDI_UTC.minus(Duration.ofDays(3)),
                MIDI_UTC.minus(Duration.ofDays(7)), MIDI_UTC.minus(Duration.ofDays(2)),
                MIDI_UTC.minus(Duration.ofDays(4)), MIDI_UTC.minus(Duration.ofDays(10)));
        verify(repository).findCandidates(eq(due), any());
        verify(repository).isStillCandidate(eq(u.getId()), eq(due), any());
    }

    @Test
    void casIntrouvable_ignoreSansIncrementer() {
        UserEntity u = user("ML", 0);
        candidates(u);
        when(repository.reminderCase(u.getId())).thenReturn(null);

        scheduler.remind();

        assertThat(u.getKycReminderCount()).isZero();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(dispatcher, auditService);
    }

    @Test
    void compteDisparu_ignore() {
        UserEntity u = user("ML", 0);
        candidates(u);
        when(userRepository.findById(u.getId())).thenReturn(Optional.empty());

        scheduler.remind();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void horsFenetreLocale_rienNePart() {
        // 21 h locales : ML (UTC+0) 21:00Z, CM (UTC+1) 20:00Z, US (New York, UTC-5 en novembre) 02:00Z le lendemain.
        assertHorsFenetre("ML", Instant.parse("2026-11-10T21:00:00Z"));
        assertHorsFenetre("CM", Instant.parse("2026-11-10T20:00:00Z"));
        assertHorsFenetre("US", Instant.parse("2026-11-11T02:00:00Z"));

        // Puis 11 h locales à Bamako : l'envoi part.
        scheduler = scheduler(Instant.parse("2026-11-10T11:00:00Z"), props(true, List.of()));
        UserEntity u = user("ML", 0);
        candidates(u);
        scheduler.remind();
        verify(dispatcher).notifyUser(eq(u.getId()), anyString(), anyString(), anyMap());
    }

    private void assertHorsFenetre(String country, Instant now) {
        scheduler = scheduler(now, props(true, List.of()));
        UserEntity u = user(country, 0);
        candidates(u);
        scheduler.remind();
        assertThat(u.getKycReminderCount()).as(country).isZero();
        verify(userRepository, never()).save(u);
        verify(dispatcher, never()).notifyUser(eq(u.getId()), anyString(), anyString(), anyMap());
    }

    @Test
    void echecEnvoiSurUnCompte_lesAutresPartent() {
        UserEntity broken = user("ML", 0);
        UserEntity fine = user("ML", 0);
        candidates(broken, fine);
        doThrow(new IllegalStateException("FCM down")).when(dispatcher)
                .notifyUser(eq(broken.getId()), anyString(), anyString(), anyMap());

        scheduler.remind();

        verify(dispatcher).notifyUser(eq(fine.getId()), anyString(), anyString(), anyMap());
        verify(auditService, never()).log(eq("USER"), eq(broken.getId()), anyString(), any(), any());
        verify(auditService).log(eq("USER"), eq(fine.getId()), eq("KYC_VERIFICATION_REMINDER_SENT"), any(), any());
        assertThat(broken.getKycReminderCount()).isEqualTo(1);
        assertThat(transactions.get()).isEqualTo(2);
    }

    @Test
    void echecReservationSurUnCompte_lesAutresPartent() {
        UserEntity broken = user("ML", 0);
        UserEntity fine = user("ML", 0);
        candidates(broken, fine);
        when(userRepository.save(broken)).thenThrow(new IllegalStateException("verrou"));

        scheduler.remind();

        verify(dispatcher, never()).notifyUser(eq(broken.getId()), anyString(), anyString(), anyMap());
        verify(dispatcher).notifyUser(eq(fine.getId()), anyString(), anyString(), anyMap());
    }

    @Test
    void casRefuse_texteRejected() {
        UserEntity u = user("ML", 0);
        candidates(u);
        when(repository.reminderCase(u.getId())).thenReturn("rejected");

        scheduler.remind();

        verify(dispatcher).notifyUser(eq(u.getId()), eq("Vous pouvez réessayer la vérification"), anyString(),
                eq(Map.of("type", "KYC_ACTION_REQUIRED", "reminder", "true", "attempt", "1", "case", "rejected")));
    }

    @Test
    void troisiemeRelance_texteAttempt3() {
        UserEntity u = user("ML", 2);
        candidates(u);

        scheduler.remind();

        assertThat(u.getKycReminderCount()).isEqualTo(3);
        verify(dispatcher).notifyUser(eq(u.getId()), eq("Dernier rappel : votre vérification"), anyString(),
                eq(Map.of("type", "KYC_ACTION_REQUIRED", "reminder", "true", "attempt", "3", "case", "incomplete")));
    }

    @Test
    void requete_delaisSoustraitsDeMaintenant_etCodesEnMajuscules() {
        scheduler = scheduler(MIDI_UTC, props(true, List.of("image_too_blurry", "Document_Expired")));
        when(repository.findCandidates(any(), any())).thenReturn(List.of());

        scheduler.remind();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> codes = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).findCandidates(any(), codes.capture());
        assertThat(codes.getValue()).containsExactlyInAnyOrder("IMAGE_TOO_BLURRY", "DOCUMENT_EXPIRED");
        verifyNoInteractions(userRepository, dispatcher);
    }
}
