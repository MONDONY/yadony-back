package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BaseEntity;
import com.yadony.api.requests.entity.NegotiationMessageEntity;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.event.NegotiationNudgeSentEvent;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NegotiationAutoReminderSchedulerTest {

    @Mock NegotiationThreadRepository threadRepo;
    @Mock NegotiationMessageRepository messageRepo;
    @Mock PackageRequestRepository requestRepo;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;

    NegotiationAutoReminderScheduler scheduler;
    final AtomicInteger transactions = new AtomicInteger();

    // Midi en France : dans la plage 9 h-20 h du destinataire.
    static final Instant NOON_PARIS = Instant.parse("2026-10-26T11:00:00Z");
    static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOON_PARIS, ZoneOffset.UTC);

    UUID travelerId = UUID.randomUUID();
    UUID senderId = UUID.randomUUID();
    UserEntity traveler;
    UserEntity sender;
    PackageRequestEntity request;
    NegotiationThreadEntity thread;

    @BeforeEach
    void setUp() {
        TransactionOperations countingTx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                transactions.incrementAndGet();
                return TransactionOperations.withoutTransaction().execute(action);
            }
        };
        scheduler = new NegotiationAutoReminderScheduler(threadRepo, messageRepo, requestRepo,
                userRepository, eventPublisher, auditService, countingTx);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "delay", Duration.ofHours(24));
        ReflectionTestUtils.setField(scheduler, "maxAge", Duration.ofHours(48));
        ReflectionTestUtils.setField(scheduler, "windowStartHour", 9);
        ReflectionTestUtils.setField(scheduler, "windowEndHour", 20);

        traveler = user(travelerId, "Awa", "Diop", "FR");
        sender = user(senderId, "Ibrahima", "Sow", "FR");
        request = new PackageRequestEntity();
        request.setSenderId(senderId);
        setId(request, UUID.randomUUID());
        thread = new NegotiationThreadEntity();
        thread.setPackageRequestId(request.getId());
        thread.setTravelerId(travelerId);
        thread.setStatus(NegotiationThreadStatus.OPEN);
        thread.setLastActivityAt(NOW_UTC.minusHours(25));
        setId(thread, UUID.randomUUID());

        lenient().when(threadRepo.findAutoReminderCandidates(any(), any())).thenReturn(List.of(thread.getId()));
        lenient().when(threadRepo.findById(thread.getId())).thenReturn(Optional.of(thread));
        lenient().when(requestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        // Par défaut, le voyageur a fait la dernière offre : c'est l'expéditeur qui doit répondre.
        lastMessageFrom(travelerId);
    }

    private static UserEntity user(UUID id, String first, String last, String country) {
        UserEntity u = new UserEntity();
        u.setFirstName(first);
        u.setLastName(last);
        u.setCountry(country);
        setId(u, id);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, BaseEntity.class, "id", id, UUID.class);
    }

    private void lastMessageFrom(UUID fromUserId) {
        NegotiationMessageEntity first = new NegotiationMessageEntity();
        first.setFromUserId(fromUserId.equals(travelerId) ? senderId : travelerId);
        NegotiationMessageEntity last = new NegotiationMessageEntity();
        last.setFromUserId(fromUserId);
        lenient().when(messageRepo.findByThreadIdOrderByCreatedAtAsc(thread.getId()))
                .thenReturn(List.of(first, last));
    }

    private NegotiationNudgeSentEvent published() {
        ArgumentCaptor<NegotiationNudgeSentEvent> captor = ArgumentCaptor.forClass(NegotiationNudgeSentEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("éteint : aucune lecture, aucun rappel")
    void disabled_doesNothing() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.remind();

        verify(threadRepo, never()).findAutoReminderCandidates(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("le voyageur attend : l'expéditeur reçoit le rappel, réservé dans sa transaction")
    void remindsSender_whenTravelerIsWaiting() {
        int sent = scheduler.remindAt(NOON_PARIS);

        assertThat(sent).isEqualTo(1);
        assertThat(transactions.get()).isEqualTo(1);
        NegotiationNudgeSentEvent event = published();
        assertThat(event.threadId()).isEqualTo(thread.getId());
        assertThat(event.packageRequestId()).isEqualTo(request.getId());
        assertThat(event.fromUserId()).isEqualTo(travelerId);
        assertThat(event.toUserId()).isEqualTo(senderId);
        assertThat(event.fromUserName()).isEqualTo("Awa D.");
        assertThat(thread.getAutoReminderAt()).isEqualTo(NOW_UTC);
        // Le rappel ne compte pas comme une activité : le fil garde son ancienneté.
        assertThat(thread.getLastActivityAt()).isEqualTo(NOW_UTC.minusHours(25));
        verify(threadRepo).save(thread);
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(thread.getId()), eq("AUTO_REMINDER_SENT"),
                eq(null), eq(Map.of("source", "SYSTEM", "target", senderId.toString())));
    }

    @Test
    @DisplayName("l'expéditeur attend : le voyageur reçoit le rappel")
    void remindsTraveler_whenSenderIsWaiting() {
        lastMessageFrom(senderId);

        scheduler.remindAt(NOON_PARIS);

        NegotiationNudgeSentEvent event = published();
        assertThat(event.fromUserId()).isEqualTo(senderId);
        assertThat(event.toUserId()).isEqualTo(travelerId);
        assertThat(event.fromUserName()).isEqualTo("Ibrahima S.");
    }

    @Test
    @DisplayName("la requête de candidats reçoit les bornes : silence de 24 h, fil de 48 h au plus")
    void passesTimeBoundsToTheQuery() {
        scheduler.remindAt(NOON_PARIS);

        verify(threadRepo).findAutoReminderCandidates(NOW_UTC.minusHours(24), NOW_UTC.minusHours(48));
    }

    @Test
    @DisplayName("fil qui n'est plus OPEN depuis la lecture : ignoré")
    void skips_threadNoLongerOpen() {
        thread.setStatus(NegotiationThreadStatus.CANCELLED);

        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(threadRepo, never()).save(any());
    }

    @Test
    @DisplayName("message arrivé depuis la lecture : pas de rappel")
    void skips_threadWithRecentActivity() {
        thread.setLastActivityAt(NOW_UTC.minusHours(2));

        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("déjà rappelé depuis la dernière activité : un seul rappel par période d'attente")
    void skips_alreadyRemindedSinceLastActivity() {
        thread.setAutoReminderAt(NOW_UTC.minusHours(1));

        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("ancien rappel antérieur à un nouveau message : le fil est de nouveau éligible")
    void reminds_whenPreviousReminderPredatesLastActivity() {
        thread.setAutoReminderAt(NOW_UTC.minusHours(60));

        assertThat(scheduler.remindAt(NOON_PARIS)).isEqualTo(1);
    }

    @Test
    @DisplayName("relance manuelle récente : pas de rappel automatique en plus")
    void skips_recentManualNudge() {
        thread.setLastNudgeAt(NOW_UTC.minusHours(3));

        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("relance manuelle ancienne : le rappel automatique part")
    void reminds_whenManualNudgeIsOld() {
        thread.setLastNudgeAt(NOW_UTC.minusHours(30));

        assertThat(scheduler.remindAt(NOON_PARIS)).isEqualTo(1);
    }

    @Test
    @DisplayName("la nuit chez le destinataire : on attend le prochain passage, rien n'est réservé")
    void skips_outsideRecipientWindow() {
        // 02:00 à Paris (UTC+2 en octobre : 00:00 UTC).
        Instant night = Instant.parse("2026-10-26T00:00:00Z");
        LocalDateTime nightUtc = LocalDateTime.ofInstant(night, ZoneOffset.UTC);
        thread.setLastActivityAt(nightUtc.minusHours(25));

        assertThat(scheduler.remindAt(night)).isZero();

        assertThat(thread.getAutoReminderAt()).isNull();
        verify(threadRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("fil sans message, demande ou utilisateur introuvable : ignoré")
    void skips_incompleteData() {
        lenient().when(messageRepo.findByThreadIdOrderByCreatedAtAsc(thread.getId())).thenReturn(List.of());
        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        lastMessageFrom(travelerId);
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.empty());
        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        lenient().when(requestRepo.findById(request.getId())).thenReturn(Optional.empty());
        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        lenient().when(threadRepo.findById(thread.getId())).thenReturn(Optional.empty());
        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("échec de réservation sur un fil : les autres sont traités")
    void reservationFailure_doesNotBlockOtherThreads() {
        NegotiationThreadEntity broken = new NegotiationThreadEntity();
        setId(broken, UUID.randomUUID());
        when(threadRepo.findAutoReminderCandidates(any(), any()))
                .thenReturn(List.of(broken.getId(), thread.getId()));
        when(threadRepo.findById(broken.getId())).thenThrow(new IllegalStateException("conflit de version"));

        assertThat(scheduler.remindAt(NOON_PARIS)).isEqualTo(1);

        assertThat(thread.getAutoReminderAt()).isEqualTo(NOW_UTC);
    }

    @Test
    @DisplayName("échec de publication : compté comme non envoyé, la boucle continue")
    void dispatchFailure_isNotCountedAsSent() {
        doThrow(new IllegalStateException("bus indisponible")).when(eventPublisher).publishEvent(any(Object.class));

        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();
    }

    @Test
    @DisplayName("aucun candidat : rien à publier")
    void noCandidates() {
        when(threadRepo.findAutoReminderCandidates(any(), any())).thenReturn(List.of());

        assertThat(scheduler.remindAt(NOON_PARIS)).isZero();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("remind() : passe à l'horloge réelle quand le rappel est allumé")
    void remind_runsWhenEnabled() {
        when(threadRepo.findAutoReminderCandidates(any(), any())).thenReturn(List.of());

        scheduler.remind();

        verify(threadRepo).findAutoReminderCandidates(any(), any());
    }
}
