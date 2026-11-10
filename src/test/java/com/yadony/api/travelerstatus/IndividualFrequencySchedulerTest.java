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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Le magasin de drapeaux est une liste en mémoire, pour enchaîner plusieurs passages. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndividualFrequencySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:30:00Z");

    @Mock IndividualFrequencyFlagRepository flags;
    @Mock IndividualFrequencyEvaluator evaluator;
    @Mock PlatformSettingsService settings;
    @Mock ApplicationEventPublisher events;

    final List<IndividualFrequencyFlagEntity> store = new ArrayList<>();
    final Map<UUID, IndividualFrequencyEvaluator.Detection> detected = new HashMap<>();
    final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(settings.individualTripsAlertThreshold()).thenReturn(4);
        when(evaluator.detect(any(), anyInt())).thenAnswer(inv -> new HashMap<>(detected));
        when(flags.findByResolvedAtIsNull()).thenAnswer(inv ->
                store.stream().filter(f -> f.getResolvedAt() == null).toList());
        when(flags.saveAll(any())).thenAnswer(inv -> {
            Iterable<IndividualFrequencyFlagEntity> in = inv.getArgument(0);
            in.forEach(f -> {
                if (f.getId() == null) {
                    ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
                }
                if (!store.contains(f)) store.add(f);
            });
            return in;
        });
    }

    private IndividualFrequencyScheduler scheduler(Boolean enabled) {
        return new IndividualFrequencyScheduler(flags, evaluator, settings, events,
                new IndividualFrequencyProperties(null, enabled), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void creeUnDrapeau_etPublieUneSeuleQuestion() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        scheduler(true).run();

        assertThat(store).singleElement().satisfies(f -> {
            assertThat(f.getUserId()).isEqualTo(userId);
            assertThat(f.getTripsCount()).isEqualTo(5);
            assertThat(f.getWindowDays()).isEqualTo(30);
            assertThat(f.getThreshold()).isEqualTo(4);
            assertThat(f.isWeeklyApplication()).isFalse();
            assertThat(f.getDetectedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        });
        ArgumentCaptor<IndividualFrequencyFlaggedEvent> event =
                ArgumentCaptor.forClass(IndividualFrequencyFlaggedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(userId);
        assertThat(event.getValue().flagId()).isEqualTo(store.get(0).getId());
        verify(evaluator).detect(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), 4);
    }

    @Test
    void doubleExecution_pasDeDoublon_niDeSecondeQuestion() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        scheduler(true).run();
        scheduler(true).run();

        assertThat(store).hasSize(1);
        verify(events, times(1)).publishEvent(any(IndividualFrequencyFlaggedEvent.class));
    }

    @Test
    void resoutLeDrapeau_quandLaConditionCesse_puisEnRecreeUnSiElleReprend() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));
        scheduler(true).run();

        detected.clear();
        scheduler(true).run();
        assertThat(store).singleElement().satisfies(f -> assertThat(f.getResolvedAt()).isNotNull());

        detected.put(userId, new IndividualFrequencyEvaluator.Detection(6, false));
        scheduler(true).run();
        assertThat(store).hasSize(2);
        assertThat(store.get(1).getResolvedAt()).isNull();
        verify(events, times(2)).publishEvent(any(IndividualFrequencyFlaggedEvent.class));
    }

    @Test
    void drapeauEcarte_nonResolu_bloqueLaRecreation() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));
        scheduler(true).run();
        store.get(0).setDismissedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        store.get(0).setDismissedBy(UUID.randomUUID());

        scheduler(true).run();

        assertThat(store).hasSize(1);
        assertThat(store.get(0).getResolvedAt()).isNull();
        verify(events, times(1)).publishEvent(any(IndividualFrequencyFlaggedEvent.class));
    }

    @Test
    void declencheurHebdomadaire_estNoteSurLeDrapeau() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(1, true));

        scheduler(true).run();

        assertThat(store).singleElement().satisfies(f -> {
            assertThat(f.isWeeklyApplication()).isTrue();
            assertThat(f.getTripsCount()).isEqualTo(1);
        });
    }

    @Test
    void desactive_neFaitRien() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        scheduler(false).run();

        verifyNoInteractions(flags, evaluator, events);
        assertThat(store).isEmpty();
    }

    @Test
    void activeParDefaut_quandLaProprieteManque() {
        detected.put(userId, new IndividualFrequencyEvaluator.Detection(5, false));

        scheduler(null).run();

        assertThat(store).hasSize(1);
    }

    @Test
    void rienADire_aucuneEcriture() {
        scheduler(true).run();

        verify(flags, never()).saveAll(any());
        verify(events, never()).publishEvent(any());
        verify(evaluator).detect(any(), eq(4));
    }
}
