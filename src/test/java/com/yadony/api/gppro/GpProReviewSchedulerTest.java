package com.yadony.api.gppro;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Le magasin de signalements est simulé avec une liste en mémoire pour enchaîner plusieurs passages. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GpProReviewSchedulerTest {

    @Mock UserRepository users;
    @Mock GpProReviewFlagRepository flags;
    @Mock GpProReviewEvaluator evaluator;

    List<GpProReviewFlagEntity> store = new ArrayList<>();
    UserEntity user;
    UUID userId = UUID.randomUUID();
    Set<GpProReviewReason> active = EnumSet.noneOf(GpProReviewReason.class);

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        when(users.findByGpProTrue()).thenReturn(List.of(user));
        when(evaluator.reasonsFor(any(), any())).thenAnswer(inv -> EnumSet.copyOf(
                active.isEmpty() ? EnumSet.noneOf(GpProReviewReason.class) : active));
        when(flags.findByUserIdAndResolvedAtIsNull(userId)).thenAnswer(inv ->
                store.stream().filter(f -> f.getResolvedAt() == null).toList());
        when(flags.saveAll(any())).thenAnswer(inv -> {
            Iterable<GpProReviewFlagEntity> in = inv.getArgument(0);
            in.forEach(f -> { if (!store.contains(f)) store.add(f); });
            return in;
        });
    }

    private GpProReviewScheduler scheduler(Boolean enabled) {
        return new GpProReviewScheduler(users, flags, evaluator, new GpProProperties(null, null, enabled));
    }

    @Test
    void createsFlagOnce() {
        active.add(GpProReviewReason.OPEN_REPORT);
        GpProReviewScheduler s = scheduler(true);
        s.run();
        s.run();
        assertThat(store).hasSize(1);
        assertThat(store.get(0).getReason()).isEqualTo(GpProReviewReason.OPEN_REPORT);
        assertThat(store.get(0).getUserId()).isEqualTo(userId);
        assertThat(store.get(0).getDetectedAt()).isNotNull();
    }

    @Test
    void resolvesFlagWhenConditionClears() {
        active.add(GpProReviewReason.OPEN_REPORT);
        GpProReviewScheduler s = scheduler(true);
        s.run();
        active.clear();
        s.run();
        assertThat(store).hasSize(1);
        assertThat(store.get(0).getResolvedAt()).isNotNull();
    }

    @Test
    void dismissedFlagNotRecreatedWhileConditionPersists() {
        active.add(GpProReviewReason.LOW_RATING);
        GpProReviewScheduler s = scheduler(true);
        s.run();
        store.get(0).setDismissedAt(LocalDateTime.now());
        s.run();
        assertThat(store).hasSize(1);
        assertThat(store.get(0).getResolvedAt()).isNull();
    }

    @Test
    void dismissedFlagIsResolvedWhenConditionClears() {
        active.add(GpProReviewReason.LOW_RATING);
        GpProReviewScheduler s = scheduler(true);
        s.run();
        store.get(0).setDismissedAt(LocalDateTime.now());
        active.clear();
        s.run();
        assertThat(store.get(0).getResolvedAt()).isNotNull();
    }

    @Test
    void reasonReappearsAfterClearingThenReturning() {
        active.add(GpProReviewReason.LOW_RATING);
        GpProReviewScheduler s = scheduler(true);
        s.run();
        store.get(0).setDismissedAt(LocalDateTime.now());
        active.clear();
        s.run();
        active.add(GpProReviewReason.LOW_RATING);
        s.run();
        assertThat(store).hasSize(2);
        assertThat(store.get(1).getResolvedAt()).isNull();
        assertThat(store.get(1).getDismissedAt()).isNull();
    }

    @Test
    void disabledSchedulerDoesNothing() {
        active.add(GpProReviewReason.OPEN_REPORT);
        scheduler(false).run();
        verifyNoInteractions(users);
        verify(flags, never()).saveAll(any());
        assertThat(store).isEmpty();
    }
}
