package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsernamePurgeSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:15:00Z");

    @Mock ReleasedUsernameRepository releasedRepository;

    @Test
    void purgeExpired_supprimeLesReservationsDePlusDe30Jours() {
        when(releasedRepository.deleteByReleasedAtBefore(OffsetDateTime.ofInstant(NOW.minusSeconds(30L * 86400),
                ZoneOffset.UTC))).thenReturn(3);
        new UsernamePurgeScheduler(releasedRepository, Clock.fixed(NOW, ZoneOffset.UTC)).purgeExpired();
        verify(releasedRepository).deleteByReleasedAtBefore(
                OffsetDateTime.ofInstant(NOW.minusSeconds(30L * 86400), ZoneOffset.UTC));
    }

    @Test
    void purgeExpired_idempotent_secondPassageSansRienASupprimer() {
        when(releasedRepository.deleteByReleasedAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2).thenReturn(0);
        UsernamePurgeScheduler scheduler =
                new UsernamePurgeScheduler(releasedRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatCode(() -> {
            scheduler.purgeExpired();
            scheduler.purgeExpired();
        }).doesNotThrowAnyException();
        verify(releasedRepository, times(2)).deleteByReleasedAtBefore(org.mockito.ArgumentMatchers.any());
    }
}
