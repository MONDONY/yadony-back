package com.yadony.api.admin;

import com.yadony.api.admin.dto.DeletionImpactResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeletionImpactServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;

    private UserEntity user(UUID id, String firstName, String lastName) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return u;
    }

    private UserDeletionImpactService service(UserDeletionImpactContributor... contributors) {
        return new UserDeletionImpactService(List.of(contributors), userRepository);
    }

    @Test
    @DisplayName("les constats sont triés du bloquant vers l'informatif")
    void findings_areSortedBySeverity() {
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
        UserDeletionImpactService service = service(
                id -> List.of(ImpactFinding.plain(ImpactSeverity.INFO, "RATINGS_GIVEN", 3)),
                id -> List.of(ImpactFinding.plain(ImpactSeverity.BLOCKING, "ACTIVE_ESCROW", 1)),
                id -> List.of(ImpactFinding.plain(ImpactSeverity.WARNING, "OPEN_DISPUTE", 2)));

        DeletionImpactResponse report = service.report(USER_ID);

        assertThat(report.findings()).extracting(DeletionImpactResponse.Finding::code)
                .containsExactly("ACTIVE_ESCROW", "OPEN_DISPUTE", "RATINGS_GIVEN");
    }

    @Test
    @DisplayName("un constat bloquant marque le rapport comme bloqué")
    void blockingFinding_marksReportBlocked() {
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
        assertThat(service(id -> List.of(
                ImpactFinding.plain(ImpactSeverity.BLOCKING, "ACTIVE_ESCROW", 1)))
                .report(USER_ID).blocked()).isTrue();
    }

    @Test
    @DisplayName("sans constat bloquant, le rapport n'est pas bloqué")
    void warningsOnly_isNotBlocked() {
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
        assertThat(service(id -> List.of(
                ImpactFinding.plain(ImpactSeverity.WARNING, "OPEN_DISPUTE", 2)))
                .report(USER_ID).blocked()).isFalse();
    }

    // Le nom ne peut pas venir des contributeurs : UserRepository vit dans auth, et l'y faire
    // injecter par chaque package recréerait le couplage qu'on évite, avec un N+1 en prime.
    @Test
    @DisplayName("les noms des contreparties sont résolus en un seul appel")
    void counterpartyNames_areResolvedInOneBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(user(a, "Awa", "Diop"), user(b, "Moussa", "Traoré")));

        DeletionImpactResponse report = service(id -> List.of(
                ImpactFinding.of(ImpactSeverity.WARNING, "PARCEL_IN_TRANSIT", List.of(
                        new ImpactFinding.AffectedParty(a, UUID.randomUUID()),
                        new ImpactFinding.AffectedParty(b, UUID.randomUUID()))))).report(USER_ID);

        assertThat(report.findings().getFirst().parties())
                .extracting(DeletionImpactResponse.Party::displayName)
                .containsExactly("Awa D.", "Moussa T.");
        verify(userRepository, times(1)).findAllById(any());
    }

    // Une contrepartie déjà anonymisée est filtrée par @Where(deleted_at IS NULL) et ne remonte
    // pas du repository. Laisser un nom nul afficherait une ligne vide dans l'écran.
    @Test
    @DisplayName("une contrepartie introuvable reste affichée sous une étiquette explicite")
    void unresolvedCounterparty_getsPlaceholder() {
        when(userRepository.findAllById(any())).thenReturn(List.of());

        DeletionImpactResponse report = service(id -> List.of(
                ImpactFinding.of(ImpactSeverity.WARNING, "OPEN_DISPUTE", List.of(
                        new ImpactFinding.AffectedParty(UUID.randomUUID(), UUID.randomUUID())))))
                .report(USER_ID);

        assertThat(report.findings().getFirst().parties().getFirst().displayName())
                .isEqualTo("Compte supprimé");
    }

    @Test
    @DisplayName("aucun constat : rapport vide et non bloqué, sans requête de noms")
    void noFinding_skipsNameLookup() {
        DeletionImpactResponse report = service(id -> List.of()).report(USER_ID);

        assertThat(report.findings()).isEmpty();
        assertThat(report.blocked()).isFalse();
        verifyNoInteractions(userRepository);
    }
}
