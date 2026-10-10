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
}
