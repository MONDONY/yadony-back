package com.yadony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    // --- Ronde de correction 1 ---

    @Test
    @DisplayName("appelé depuis une transaction en lecture seule -> échec explicite, jamais un succès silencieux")
    void refusesWhenCalledFromReadOnlyTransaction() {
        // FlushMode.MANUAL en lecture seule : un INSERT accepté ici ne serait jamais émis.
        // C'est le pire mode de défaillance possible (succès apparent), donc on le refuse
        // bruyamment plutôt que de risquer de le masquer.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThatThrownBy(() -> provisioner.resolveOrProvision("uid-readonly"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("ligne soft-deleted portant le même firebase_uid -> réactivée, jamais réinsérée")
    void reactivatesSoftDeletedRowInsteadOfReinserting() {
        UserEntity deleted = new UserEntity();
        deleted.setFirebaseUid("uid-3");
        when(userRepository.findByFirebaseUidIncludingDeleted("uid-3")).thenReturn(Optional.of(deleted));

        UUID reactivatedId = UUID.randomUUID();
        UserEntity reactivated = new UserEntity();
        reactivated.setFirebaseUid("uid-3");
        org.springframework.test.util.ReflectionTestUtils.setField(reactivated, "id", reactivatedId);
        // 1er appel (vérification "ligne active ?") -> absente ; 2e appel (après l'UPDATE
        // natif de réactivation) -> la ligne redevenue visible.
        when(userRepository.findByFirebaseUid("uid-3"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reactivated));

        UUID id = provisioner.resolveOrProvision("uid-3");

        assertThat(id).isEqualTo(reactivatedId);
        verify(userRepository).reactivateByFirebaseUid("uid-3", UserStatus.ACTIVE.name());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("course sur l'insertion -> relecture, jamais un 500")
    void reReadsOnConcurrentInsertRace() {
        when(userRepository.findByFirebaseUidIncludingDeleted("uid-4")).thenReturn(Optional.empty());
        when(usernameGenerator.generate()).thenReturn("visiteur-xy9z");
        when(userRepository.save(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_users_firebase_uid"));

        UUID concurrentId = UUID.randomUUID();
        UserEntity concurrentlyInserted = new UserEntity();
        concurrentlyInserted.setFirebaseUid("uid-4");
        org.springframework.test.util.ReflectionTestUtils.setField(concurrentlyInserted, "id", concurrentId);
        // 1er appel (résolution initiale) -> absente ; 2e appel (relecture après l'échec de
        // l'insert, dans le catch) -> la ligne insérée par le concurrent entretemps.
        when(userRepository.findByFirebaseUid("uid-4"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentlyInserted));

        UUID id = provisioner.resolveOrProvision("uid-4");

        assertThat(id).isEqualTo(concurrentId);
    }
}
