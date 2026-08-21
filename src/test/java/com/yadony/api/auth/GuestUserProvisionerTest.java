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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("ligne soft-deleted SANS rôle (ligne invitée pure) -> réactivée, jamais réinsérée")
    void reactivatesSoftDeletedRowInsteadOfReinserting() {
        // roles vide par défaut (HashSet neuf) : c'est précisément ce qui distingue une
        // ligne invitée pure d'un ancien compte réel (ronde de correction 2).
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

    // --- Ronde de correction 2 : régression de sécurité sur la réactivation ---
    //
    // AccountFinalizationService.finalize() (suppression RGPD / bannissement d'un VRAI
    // compte) soft-delete la ligne SANS jamais vider `roles`. Réactiver une telle ligne ici
    // ressusciterait un compte banni/supprimé hors du tunnel /auth/register — sans reset de
    // ses champs pseudonymisés, sans entrée d'audit. Une ligne soft-deleted portant le
    // moindre rôle n'est jamais une ligne invitée pure : le provisioner doit refuser de la
    // toucher, laissant /auth/register (seul habilité) faire ce travail.

    @Test
    @DisplayName("Ronde 2 : ligne soft-deleted PORTANT DES RÔLES -> jamais réactivée, ancien compte réel")
    void refusesToReactivateSoftDeletedRowWithRoles() {
        UserEntity deletedRealAccount = new UserEntity();
        deletedRealAccount.setFirebaseUid("uid-5");
        deletedRealAccount.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid("uid-5")).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUidIncludingDeleted("uid-5"))
                .thenReturn(Optional.of(deletedRealAccount));

        // La ligne reste supprimée : le seul mécanisme qui efface deleted_at
        // (reactivateByFirebaseUid) n'est jamais invoqué. Rien n'est retourné à l'appelant :
        // resolveOrProvision lève avant tout `return`, donc ni l'id ni les rôles de cet
        // ancien compte ne fuient jamais hors de ce provisioner.
        assertThatThrownBy(() -> provisioner.resolveOrProvision("uid-5"))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).reactivateByFirebaseUid(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Ronde 2 : la ligne à rôles reste supprimée, aucune tentative de résurrection")
    void softDeletedRowWithRoles_neverReactivatedNorLeaked() {
        UserEntity deletedAdmin = new UserEntity();
        deletedAdmin.setFirebaseUid("uid-7");
        deletedAdmin.setRoles(Set.of(Role.ADMIN));
        when(userRepository.findByFirebaseUid("uid-7")).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUidIncludingDeleted("uid-7"))
                .thenReturn(Optional.of(deletedAdmin));

        assertThatThrownBy(() -> provisioner.resolveOrProvision("uid-7"))
                .isInstanceOf(IllegalStateException.class);

        // Preuve que le contrôle a bien eu lieu (pas juste esquivé), et que la ligne
        // conserve son deleted_at (aucun appel à la réactivation ni à une nouvelle création).
        verify(userRepository).findByFirebaseUidIncludingDeleted("uid-7");
        verify(userRepository, never()).reactivateByFirebaseUid(eq("uid-7"), any());
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
