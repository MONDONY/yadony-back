package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReliabilityServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ReliabilityService service = new ReliabilityService(userRepository);

    private UserEntity user() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void livresEtCompteursDeReputation_donnentLeTaux() {
        UserEntity user = user();
        user.setCancellationCount(1);
        when(userRepository.countCompletedShipments(user.getId())).thenReturn(9L);

        assertThat(service.reliabilityPercent(user)).isEqualTo(90);
    }

    @Test
    void sousLeSeuil_null() {
        UserEntity user = user();
        when(userRepository.countCompletedShipments(user.getId())).thenReturn(2L);

        assertThat(service.reliabilityPercent(user)).isNull();
    }

    @Test
    void echecDuCalcul_null_sansLever() {
        UserEntity user = user();
        when(userRepository.countCompletedShipments(user.getId())).thenThrow(new RuntimeException("db"));

        assertThat(service.reliabilityPercent(user)).isNull();
    }
}
