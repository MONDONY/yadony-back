package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserProStatusChangedEvent;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProAccessSynchronizerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private ProAccessSynchronizer synchronizer() {
        return new ProAccessSynchronizer(userRepository, eventPublisher);
    }

    private UserEntity user(boolean pro) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setProAccount(pro);
        return u;
    }

    @Test
    @DisplayName("ouvrir l'accès pose le drapeau et publie l'événement")
    void grantsAccess() {
        UserEntity u = user(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        synchronizer().sync(USER_ID, true);

        assertThat(u.isProAccount()).isTrue();
        verify(userRepository).save(u);

        ArgumentCaptor<UserProStatusChangedEvent> captor =
                ArgumentCaptor.forClass(UserProStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().isPro()).isTrue();
    }

    @Test
    @DisplayName("fermer l'accès retire le drapeau et publie l'événement")
    void revokesAccess() {
        UserEntity u = user(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        synchronizer().sync(USER_ID, false);

        assertThat(u.isProAccount()).isFalse();
        verify(eventPublisher).publishEvent(any(UserProStatusChangedEvent.class));
    }

    @Test
    @DisplayName("aucun événement si le drapeau est déjà dans l'état voulu")
    void noEventWhenAlreadyInTargetState() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(true)));

        synchronizer().sync(USER_ID, true);

        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(UserProStatusChangedEvent.class));
    }

    @Test
    @DisplayName("utilisateur inconnu : aucune exception, aucun événement")
    void unknownUserIsIgnored() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        synchronizer().sync(USER_ID, true);

        verify(eventPublisher, never()).publishEvent(any(UserProStatusChangedEvent.class));
    }
}
