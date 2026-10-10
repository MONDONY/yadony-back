package com.yadony.api.common.media;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PhotoPurgeRunnerTest {

    @Mock PlatformSettingsService settings;
    @Mock PhotoPurgeRunRepository runs;
    @Mock AdminAlertService alerts;
    @Mock PhotoPurgeParticipant tracking;
    @Mock PhotoPurgeParticipant requests;

    final Clock clock = Clock.fixed(Instant.parse("2026-10-10T10:40:00Z"), ZoneOffset.UTC);
    PhotoPurgeRunner runner;

    @BeforeEach
    void setUp() {
        runner = new PhotoPurgeRunner(List.of(tracking, requests), settings, runs, alerts, clock);
        lenient().when(settings.photoRetentionDays()).thenReturn(7);
        lenient().when(runs.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(tracking.name()).thenReturn("tracking");
        lenient().when(requests.name()).thenReturn("requests");
    }

    @Test
    void switchOff_runsDry_forEveryParticipant() {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        when(tracking.purgeDue(eq(7), eq(true), anyInt(), any())).thenReturn(new PhotoPurgeOutcome(12, 0, 0));
        when(requests.purgeDue(eq(7), eq(true), anyInt(), any())).thenReturn(new PhotoPurgeOutcome(3, 0, 0));

        PhotoPurgeRunEntity run = runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getEligible()).isEqualTo(15);
        assertThat(run.getDeleted()).isZero();
        verify(tracking, never()).purgeDue(anyInt(), eq(false), anyInt(), any());
    }

    @Test
    void oneParticipantThrows_othersStillRun_andErrorRecorded() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(tracking.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenThrow(new RuntimeException("R2 down"));
        when(requests.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(new PhotoPurgeOutcome(2, 2, 0));

        PhotoPurgeRunEntity run = runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE).orElseThrow();

        assertThat(run.getDeleted()).isEqualTo(2);
        assertThat(run.getFailed()).isEqualTo(1);
        verify(requests).purgeDue(anyInt(), eq(false), anyInt(), any());
    }

    @Test
    void concurrentRun_isRefused() {
        assertThat(runner.tryLock()).isTrue();
        try {
            assertThat(runner.runPurge(PhotoPurgeRunner.TRIGGER_ADMIN)).isEmpty();
        } finally {
            runner.unlock();
        }
        verifyNoInteractions(tracking, requests);
    }

    @Test
    void fiveConsecutiveRunsWithFailures_raisesAlert() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(tracking.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(new PhotoPurgeOutcome(1, 0, 1));
        when(requests.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(PhotoPurgeOutcome.NONE);
        PhotoPurgeRunEntity failing = new PhotoPurgeRunEntity("PURGE", false, "SCHEDULE", LocalDateTime.now());
        failing.finish(new PhotoPurgeOutcome(1, 0, 1), LocalDateTime.now());
        when(runs.findTop5ByKindOrderByStartedAtDesc("PURGE")).thenReturn(List.of(failing, failing, failing, failing, failing));

        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);

        verify(alerts).raise(eq("PHOTO_PURGE_FAILING"), anyString(), anyMap());
    }

    @Test
    void lastSuccessOlderThan48h_raisesStaleAlert() {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        when(tracking.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(PhotoPurgeOutcome.NONE);
        when(requests.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(PhotoPurgeOutcome.NONE);
        PhotoPurgeRunEntity old = new PhotoPurgeRunEntity("PURGE", true, "SCHEDULE",
                LocalDateTime.of(2026, 10, 7, 0, 0));
        old.finish(PhotoPurgeOutcome.NONE, LocalDateTime.of(2026, 10, 7, 0, 1));
        when(runs.findTopByKindAndErrorIsNullAndFinishedAtIsNotNullOrderByStartedAtDesc("PURGE"))
                .thenReturn(Optional.of(old));

        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);

        verify(alerts).raise(eq("PHOTO_PURGE_STALE"), anyString(), anyMap());
    }

    static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private void stubFailingRuns() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(tracking.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(new PhotoPurgeOutcome(1, 0, 1));
        when(requests.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(PhotoPurgeOutcome.NONE);
        PhotoPurgeRunEntity failing = new PhotoPurgeRunEntity("PURGE", false, "SCHEDULE", LocalDateTime.now());
        failing.finish(new PhotoPurgeOutcome(1, 0, 1), LocalDateTime.now());
        when(runs.findTop5ByKindOrderByStartedAtDesc("PURGE")).thenReturn(List.of(failing, failing, failing, failing, failing));
    }

    @Test
    void failingAlert_isThrottledTo24h() {
        MutableClock mc = new MutableClock(Instant.parse("2026-10-10T10:40:00Z"));
        runner = new PhotoPurgeRunner(List.of(tracking, requests), settings, runs, alerts, mc);
        stubFailingRuns();

        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);
        mc.now = mc.now.plusSeconds(3600);
        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);
        verify(alerts, times(1)).raise(eq("PHOTO_PURGE_FAILING"), anyString(), anyMap());

        mc.now = mc.now.plusSeconds(25 * 3600);
        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);
        verify(alerts, times(2)).raise(eq("PHOTO_PURGE_FAILING"), anyString(), anyMap());
    }

    @Test
    void finalSaveThrows_runMarkedFailed_lockReleased_exceptionRethrown() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(tracking.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(PhotoPurgeOutcome.NONE);
        when(requests.purgeDue(anyInt(), anyBoolean(), anyInt(), any())).thenReturn(PhotoPurgeOutcome.NONE);
        java.util.List<PhotoPurgeRunEntity> saved = new java.util.ArrayList<>();
        when(runs.save(any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            if (saved.size() >= 2) throw new IllegalStateException("db down");
            return i.getArgument(0);
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE))
                .isInstanceOf(IllegalStateException.class);

        assertThat(runner.isRunning()).isFalse();
        assertThat(saved.get(0).getError()).contains("db down");
        assertThat(saved.get(0).getFinishedAt()).isNotNull();
    }

    @Test
    void settingThrowsAfterRowCreated_rowMarkedFailed() {
        when(settings.photoPurgeEnabled()).thenReturn(true);
        when(settings.photoRetentionDays()).thenThrow(new IllegalStateException("settings down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE))
                .isInstanceOf(IllegalStateException.class);

        org.mockito.ArgumentCaptor<PhotoPurgeRunEntity> cap = org.mockito.ArgumentCaptor.forClass(PhotoPurgeRunEntity.class);
        verify(runs, atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getError()).contains("settings down");
        assertThat(cap.getAllValues().get(0).getFinishedAt()).isNotNull();
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void alertRaiseThrows_afterSuccessfulRun_runKeptClean() {
        stubFailingRuns();
        doThrow(new IllegalStateException("telegram down")).when(alerts).raise(eq("PHOTO_PURGE_FAILING"), anyString(), anyMap());

        Optional<PhotoPurgeRunEntity> run = runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);

        assertThat(run).isPresent();
        assertThat(run.get().getError()).isNull();
        assertThat(run.get().getFinishedAt()).isNotNull();
        assertThat(runner.isRunning()).isFalse();
        verify(runs, times(2)).save(any());
    }

    @Test
    void raiseThatThrows_isNotThrottled() {
        MutableClock mc = new MutableClock(Instant.parse("2026-10-10T10:40:00Z"));
        runner = new PhotoPurgeRunner(List.of(tracking, requests), settings, runs, alerts, mc);
        stubFailingRuns();
        doThrow(new IllegalStateException("telegram down")).doNothing()
                .when(alerts).raise(eq("PHOTO_PURGE_FAILING"), anyString(), anyMap());

        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);
        mc.now = mc.now.plusSeconds(3600);
        runner.runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);

        verify(alerts, times(2)).raise(eq("PHOTO_PURGE_FAILING"), anyString(), anyMap());
    }
}
