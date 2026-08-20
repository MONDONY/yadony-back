package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tâche 3 (2026-08-20) — le verrou pays ne porte plus que sur l'existence d'un
 * compte Stripe Connect : son pays est immuable chez Stripe, une fois le compte créé.
 */
@ExtendWith(MockitoExtension.class)
class CountryLockServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks CountryLockService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void isLocked_noUserFound_returnsFalse() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    @DisplayName("Un compte Stripe Connect existant verrouille le pays")
    void stripeAccountLocksCountry() {
        UserEntity user = new UserEntity();
        user.setStripeAccountId("acct_123");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(service.isLocked(userId)).isTrue();
    }

    @Test
    void isLocked_userWithBlankStripeAccountId_returnsFalse() {
        UserEntity user = new UserEntity();
        user.setStripeAccountId("");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(service.isLocked(userId)).isFalse();
    }

    @Test
    void isLocked_userWithNoStripeAccountId_returnsFalse() {
        UserEntity user = new UserEntity();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(service.isLocked(userId)).isFalse();
    }
}
