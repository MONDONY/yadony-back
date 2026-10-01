package com.yadony.api.auth;

import com.yadony.api.auth.events.UserSeenEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserActivityServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);
    private final UserActivityService service = new UserActivityService(userRepository, clock);

    @Test
    void ouvertureDeLApp_enregistreLaDerniereConnexion() {
        UUID id = UUID.randomUUID();

        service.onUserSeen(new UserSeenEvent(id));

        verify(userRepository).touchLastSeen(id, LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    @Test
    void ouverturesRapprochees_uneSeuleEcriture() {
        UUID id = UUID.randomUUID();

        service.onUserSeen(new UserSeenEvent(id));
        service.onUserSeen(new UserSeenEvent(id));
        service.onUserSeen(new UserSeenEvent(UUID.randomUUID()));

        verify(userRepository, times(1)).touchLastSeen(eq(id), any());
        verify(userRepository, times(2)).touchLastSeen(any(), any());
    }

    @Test
    void echecDEcriture_nEmpechePasLaConnexionEtSeraRetente() {
        UUID id = UUID.randomUUID();
        when(userRepository.touchLastSeen(eq(id), any()))
                .thenThrow(new IllegalStateException("base indisponible"))
                .thenReturn(1);

        service.onUserSeen(new UserSeenEvent(id));
        service.onUserSeen(new UserSeenEvent(id));

        verify(userRepository, times(2)).touchLastSeen(eq(id), any());
    }
}
