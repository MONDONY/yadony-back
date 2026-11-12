package com.yadony.api.travelerstatus;

import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.travelerstatus.events.IndividualFrequencyFlaggedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Le magasin de drapeaux est une liste en mémoire, pour enchaîner plusieurs passages à des dates différentes. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndividualFrequencySchedulerTest {

    private static final Instant DAY0 = Instant.parse("2026-11-10T04:30:00Z");

    @Mock IndividualFrequencyFlagRepository flags;
    @Mock IndividualFrequencyEvaluator evaluator;
    @Mock PlatformSettingsService settings;
    @Mock ApplicationEventPublisher events;

    final List<IndividualFrequencyFlagEntity> store = new ArrayList<>();
    final Map<UUID, IndividualFrequencyEvaluator.Detection> detected = new HashMap<>();
    final Set<UUID> failingUsers = new HashSet<>();
    final UUID userId = UUID.randomUUID();
    int threshold = 4;

    @BeforeEach
    void setUp() {
        when(settings.individualTripsAlertThreshold()).thenAnswer(inv -> threshold);
        when(evaluator.detect(any(), anyInt())).thenAnswer(inv -> new HashMap<>(detected));
        when(flags.findByResolvedAtIsNull()).thenAnswer(inv ->
                store.stream().filter(f -> f.getResolvedAt() == null).toList());
        when(flags.existsByUserIdAndDetectedAtGreaterThanEqual(any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            LocalDateTime since = inv.getArgument(1);
            return store.stream().anyMatch(f -> f.getUserId().equals(id) && !f.getDetectedAt().isBefore(since));
        });
        when(flags.saveAndFlush(any())).thenAnswer(inv -> {
            IndividualFrequencyFlagEntity f = inv.getArgument(0);
            if (failingUsers.contains(f.getUserId())) {
                throw new DataIntegrityViolationException("uq_individual_frequency_flags_open");
            }
            ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
            store.add(f);
            return f;
        });
        when(flags.resolveIfOpen(any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            LocalDateTime now = inv.getArgument(1);
            for (IndividualFrequencyFlagEntity f : store) {
                if (f.getId().equals(id) && f.getResolvedAt() == null) {
                    f.setResolvedAt(now);
                    return 1;
                }
            }
            return 0;
        });
    }

    private IndividualFrequencyScheduler scheduler(Boolean enabled, int day) {
        return new IndividualFrequencyScheduler(flags, evaluator, settings, events,
                new IndividualFrequencyProperties(null, enabled),
                Clock.fixed(DAY0.plusSeconds(86_400L * day), ZoneOffset.UTC), TransactionOperations.withoutTransaction());
    }

    private IndividualFrequencyScheduler.Outcome runOn(int day) {
        return scheduler(true, day).run();
    }

    private void verifyQuestions(int n) {
        verify(events, times(n)).publishEvent(any(IndividualFrequencyFlaggedEvent.class));
    }

    @Test
    void creeUnDrapeau_etPublieUneSeuleQuestion() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        IndividualFrequencyScheduler.Outcome outcome = runOn(0);

        assertThat(outcome).isEqualTo(new IndividualFrequencyScheduler.Outcome(1, 1, 0, 0));
        assertThat(store).singleElement().satisfies(f -> {
            assertThat(f.getUserId()).isEqualTo(userId);
            assertThat(f.getTripsCount()).isEqualTo(5);
            // Période du compte : jours du mois civil de la détection (novembre 2026 = 30).
            assertThat(f.getWindowDays()).isEqualTo(java.time.YearMonth.from(f.getDetectedAt()).lengthOfMonth());
            assertThat(f.getThreshold()).isEqualTo(4);
            assertThat(f.isWeeklyApplication()).isFalse();
            assertThat(f.getDetectedAt()).isEqualTo(LocalDateTime.ofInstant(DAY0, ZoneOffset.UTC));
        });
        ArgumentCaptor<IndividualFrequencyFlaggedEvent> event =
                ArgumentCaptor.forClass(IndividualFrequencyFlaggedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(userId);
        assertThat(event.getValue().flagId()).isEqualTo(store.get(0).getId());
        verify(evaluator).detect(LocalDateTime.ofInstant(DAY0, ZoneOffset.UTC), 4);
    }

    @Test
    void doubleExecution_pasDeDoublon_niDeSecondeQuestion() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        runOn(0);
        runOn(0);

        assertThat(store).hasSize(1);
        verifyQuestions(1);
    }

    @Test
    void oscillationAutourDuSeuil_nouveauxDrapeaux_maisUneSeuleQuestionEn90Jours() {
        for (int day = 0; day < 60; day += 2) {
            detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));
            runOn(day);
            detected.clear();
            runOn(day + 1);
        }

        // Un drapeau par remontée, tous résolus : l'équipe voit chaque alerte, le voyageur une seule question.
        assertThat(store).hasSize(30).allSatisfy(f -> assertThat(f.getResolvedAt()).isNotNull());
        verifyQuestions(1);
    }

    @Test
    void apres90JoursSansDrapeau_laQuestionPeutRevenir() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));
        runOn(0);
        detected.clear();
        runOn(1);

        detected.put(userId, new IndividualFrequencyEvaluator.Detection(6, false));
        runOn(89);
        detected.clear();
        runOn(90);
        verifyQuestions(1);

        // Dernier drapeau détecté le jour 89 : 91 jours plus tard, plus aucun dans le délai.
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(6, false));
        runOn(180);
        verifyQuestions(2);
    }

    @Test
    void seuilBaissePuisRemonteParLAdmin_uneSeuleQuestion() {
        // L'évaluateur réel dépend du seuil : 5 voyages, retenu sous un seuil de 4, pas sous un seuil de 10.
        when(evaluator.detect(any(), anyInt())).thenAnswer(inv -> {
            int t = inv.getArgument(1);
            return 5 > t ? Map.of(userId, new IndividualFrequencyEvaluator.Detection(5, false)) : Map.of();
        });
        threshold = 10;
        runOn(0);
        threshold = 4;
        runOn(1);
        threshold = 10;
        runOn(2);
        threshold = 4;
        runOn(3);

        assertThat(store).hasSize(2);
        assertThat(store.get(0).getResolvedAt()).isNotNull();
        verifyQuestions(1);
    }

    @Test
    void seuilReleve_resoutLeDrapeau() {
        when(evaluator.detect(any(), anyInt())).thenAnswer(inv -> {
            int t = inv.getArgument(1);
            return 5 > t ? Map.of(userId, new IndividualFrequencyEvaluator.Detection(5, false)) : Map.of();
        });
        runOn(0);
        threshold = 5;

        IndividualFrequencyScheduler.Outcome outcome = runOn(1);

        assertThat(outcome.resolved()).isEqualTo(1);
        assertThat(store).singleElement().satisfies(f -> assertThat(f.getResolvedAt()).isNotNull());
    }

    @Test
    void drapeauEcarte_nonResolu_bloqueLaRecreation() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));
        runOn(0);
        store.get(0).setDismissedAt(LocalDateTime.ofInstant(DAY0, ZoneOffset.UTC));
        store.get(0).setDismissedBy(UUID.randomUUID());

        runOn(1);

        assertThat(store).hasSize(1);
        assertThat(store.get(0).getResolvedAt()).isNull();
        assertThat(store.get(0).getDismissedAt()).isNotNull();
        verifyQuestions(1);
    }

    @Test
    void declencheurHebdomadaire_estNoteSurLeDrapeau() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(1, true));

        runOn(0);

        assertThat(store).singleElement().satisfies(f -> {
            assertThat(f.isWeeklyApplication()).isTrue();
            assertThat(f.getTripsCount()).isEqualTo(1);
        });
    }

    @Test
    void uneErreurSurUnUtilisateur_nEmpechePasLesAutres() {
        UUID broken = UUID.randomUUID();
        failingUsers.add(broken);
        detected.put(broken, new IndividualFrequencyEvaluator.Detection(7, false));
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        IndividualFrequencyScheduler.Outcome outcome = runOn(0);

        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.created()).isEqualTo(1);
        assertThat(store).singleElement().satisfies(f -> assertThat(f.getUserId()).isEqualTo(userId));
        verifyQuestions(1);
        verify(events).publishEvent(new IndividualFrequencyFlaggedEvent(userId, store.get(0).getId()));
    }

    @Test
    void eteintParDefaut_quandLaProprieteManque_niCompteurNiDeclencheurHebdomadaire() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));
        detected.put(UUID.randomUUID(), new IndividualFrequencyEvaluator.Detection(0, true));

        scheduler(null, 0).run();
        scheduler(false, 0).run();

        verifyNoInteractions(flags, evaluator, events);
        assertThat(store).isEmpty();
    }

    @Test
    void allumeExplicitement_fonctionne() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        scheduler(true, 0).run();

        assertThat(store).hasSize(1);
        assertThat(new IndividualFrequencyProperties(null, null).schedulerEnabledOrDefault()).isFalse();
    }

    @Test
    void rienADire_aucuneEcriture() {
        runOn(0);

        verify(flags, never()).saveAndFlush(any());
        verify(flags, never()).resolveIfOpen(any(), any());
        verify(events, never()).publishEvent(any());
        verify(evaluator).detect(any(), eq(4));
    }
}
