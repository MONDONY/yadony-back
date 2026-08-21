package com.yadony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestUserProvisioner — matérialisation paresseuse")
class GuestUserProvisionerTest {

    @Mock private UserRepository userRepository;
    @Mock private UsernameGenerator usernameGenerator;
    @InjectMocks private GuestUserProvisioner provisioner;

    @Test
    @DisplayName("ligne existante -> réutilisée, aucune création")
    void reusesExistingRow() {
        UserEntity existing = new UserEntity();
        existing.setFirebaseUid("uid-1");
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(existing));

        UUID id = provisioner.resolveOrProvision("uid-1");

        assertThat(id).isEqualTo(existing.getId());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("aucune ligne -> création avec username généré, statut actif, AUCUN rôle")
    void provisionsNewRow() {
        when(userRepository.findByFirebaseUid("uid-2")).thenReturn(Optional.empty());
        when(usernameGenerator.generate()).thenReturn("visiteur-ab12cd");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity saved = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        UUID id = provisioner.resolveOrProvision("uid-2");

        assertThat(id).isNotNull();

        org.mockito.ArgumentCaptor<UserEntity> captor =
                org.mockito.ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        UserEntity created = captor.getValue();

        assertThat(created.getFirebaseUid()).isEqualTo("uid-2");
        assertThat(created.getUsername()).isEqualTo("visiteur-ab12cd");
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        // Décisif : les rôles viennent du token, jamais de la base. Une ligne
        // invitée porteuse de rôles pourrait promouvoir un anonyme si quelqu'un
        // relâchait un jour la règle du filtre.
        assertThat(created.getRoles()).isEmpty();
    }
}
