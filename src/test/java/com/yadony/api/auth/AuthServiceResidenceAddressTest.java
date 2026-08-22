package com.yadony.api.auth;

import com.yadony.api.auth.dto.ResidenceAddressRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceResidenceAddressTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @InjectMocks private AuthService service;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        setId(user, UUID.randomUUID());
        user.setFirebaseUid("uid-1");
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    // UserEntity.id n'a pas de setter public (généré par JPA) — même helper par
    // réflexion que AuthServiceTest, dont ce test reprend le harnais à l'identique.
    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void storesAddressAndAudits() {
        service.updateResidenceAddress("uid-1",
                new ResidenceAddressRequest("12 rue des Lilas", "Bat. B", "75011", "Paris"));

        assertThat(user.getResidenceStreet()).isEqualTo("12 rue des Lilas");
        assertThat(user.getResidenceLine2()).isEqualTo("Bat. B");
        assertThat(user.getResidencePostalCode()).isEqualTo("75011");
        assertThat(user.getCity()).isEqualTo("Paris");
        verify(auditService).log(eq("USER"), eq(user.getId()),
                eq("RESIDENCE_ADDRESS_UPDATED"), eq(user.getId()), any());
    }

    @Test
    void auditPayloadCarriesNoAddress() {
        service.updateResidenceAddress("uid-1",
                new ResidenceAddressRequest("12 rue des Lilas", null, "75011", "Paris"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).log(any(), any(), any(), any(), payloadCaptor.capture());
        // Assertion structurelle : le payload ne contient QUE le fait (hasLine2),
        // jamais la rue ni le code postal, sous quelque forme que ce soit.
        assertThat(payloadCaptor.getValue()).containsOnlyKeys("hasLine2");
    }

    @Test
    void rejectsUnknownUser() {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateResidenceAddress("ghost",
                new ResidenceAddressRequest("x", null, "y", "z")))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("Utilisateur introuvable");
    }

    @Test
    void marksOnboardingSeenOnce() {
        service.markOnboardingSeen("uid-1");
        var first = user.getOnboardingSeenAt();
        assertThat(first).isNotNull();

        service.markOnboardingSeen("uid-1");
        assertThat(user.getOnboardingSeenAt()).isEqualTo(first);
    }
}
