package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserPhoneAttachedEvent;
import com.yadony.api.auth.events.UserRegisteredEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceptionCatchUpListenerTest {

    @Mock ReceptionLinker linker;
    @Mock UserRepository userRepository;
    @InjectMocks ReceptionCatchUpListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UserEntity user = new UserEntity();

    @Test
    void registration_catchesUpWithPush() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(linker.catchUpAndNotify(user)).thenReturn(1);

        listener.onUserRegistered(new UserRegisteredEvent(userId, "uid"));

        verify(linker).catchUpAndNotify(user);
    }

    @Test
    void phoneAttached_catchesUpWithPush() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(linker.catchUpAndNotify(user)).thenReturn(0);

        listener.onPhoneAttached(new UserPhoneAttachedEvent(userId));

        verify(linker).catchUpAndNotify(user);
    }

    @Test
    void unknownUser_doesNothing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        listener.onPhoneAttached(new UserPhoneAttachedEvent(userId));

        verify(linker, never()).catchUpAndNotify(any());
    }

    @Test
    void firebaseFailure_isSwallowed() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(linker.catchUpAndNotify(user)).thenThrow(new IllegalStateException("Firebase injoignable"));

        assertThatCode(() -> listener.onUserRegistered(new UserRegisteredEvent(userId, "uid")))
                .doesNotThrowAnyException();
    }
}
