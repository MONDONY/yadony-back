package com.yadony.api.activation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivationKycListenerTest {

    @Mock UserRepository userRepository;
    @InjectMocks ActivationKycListener listener;

    @Test
    void stampsKycVerifiedAt_whenAbsent() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        listener.onKycVerified(new UserKycVerifiedEvent(id));

        assertThat(user.getKycVerifiedAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void keepsFirstVerificationDate() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        Instant first = Instant.parse("2026-09-01T10:00:00Z");
        user.setKycVerifiedAt(first);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        listener.onKycVerified(new UserKycVerifiedEvent(id));

        assertThat(user.getKycVerifiedAt()).isEqualTo(first);
        verify(userRepository, never()).save(any());
    }

    @Test
    void ignoresUnknownUser() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        listener.onKycVerified(new UserKycVerifiedEvent(id));
        verify(userRepository, never()).save(any());
    }
}
