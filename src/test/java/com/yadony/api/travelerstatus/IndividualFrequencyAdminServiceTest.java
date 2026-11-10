package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.travelerstatus.dto.AdminIndividualFrequencyFlagRow;
import com.yadony.api.travelerstatus.dto.IndividualFrequencyAlert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class IndividualFrequencyAdminServiceTest {

    @Mock IndividualFrequencyFlagRepository flags;
    @Mock UserRepository users;
    @Mock AuditService audit;
    @InjectMocks IndividualFrequencyAdminService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private IndividualFrequencyFlagEntity flag() {
        IndividualFrequencyFlagEntity f = new IndividualFrequencyFlagEntity();
        ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
        f.setUserId(userId);
        f.setTripsCount(6);
        f.setWindowDays(30);
        f.setThreshold(4);
        f.setDetectedAt(LocalDateTime.of(2026, 11, 10, 4, 30));
        return f;
    }

    @Test
    void ecarter_ecritureConditionnelle_etAudite() {
        IndividualFrequencyFlagEntity f = flag();
        when(flags.findById(f.getId())).thenReturn(Optional.of(f));
        when(flags.dismissIfOpen(eq(f.getId()), eq(adminId), any())).thenReturn(1);

        service.dismiss(f.getId(), adminId);

        verify(flags).dismissIfOpen(eq(f.getId()), eq(adminId), any());
        verify(flags, never()).save(any());
        verify(audit).log("INDIVIDUAL_FREQUENCY_FLAG", f.getId(), "INDIVIDUAL_FREQUENCY_FLAG_DISMISSED", adminId,
                Map.of("userId", userId.toString(), "tripsCount", 6, "threshold", 4));
    }

    /** Déjà écarté, déjà résolu, ou devancé par un clic ou un contrôle simultané : l'UPDATE ne touche rien. */
    @Test
    void ecarter_sansLigneModifiee_pasDAudit() {
        IndividualFrequencyFlagEntity f = flag();
        when(flags.findById(f.getId())).thenReturn(Optional.of(f));
        when(flags.dismissIfOpen(eq(f.getId()), any(), any())).thenReturn(0);

        service.dismiss(f.getId(), adminId);

        verifyNoInteractions(audit);
    }

    @Test
    void ecarter_drapeauInconnu_404() {
        UUID id = UUID.randomUUID();
        when(flags.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dismiss(id, adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(audit);
    }

    @Test
    void liste_porteLeNomAffiche_etLesChiffresDuDrapeau() {
        IndividualFrequencyFlagEntity f = flag();
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        when(flags.findByResolvedAtIsNullAndDismissedAtIsNullOrderByDetectedAtAscIdAsc()).thenReturn(List.of(f));
        when(users.findAllById(List.of(userId))).thenReturn(List.of(u));

        List<AdminIndividualFrequencyFlagRow> rows = service.list();

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.flagId()).isEqualTo(f.getId());
            assertThat(r.userId()).isEqualTo(userId);
            assertThat(r.displayName()).isEqualTo(u.adminDisplayName());
            assertThat(r.tripsCount()).isEqualTo(6);
            assertThat(r.windowDays()).isEqualTo(30);
            assertThat(r.threshold()).isEqualTo(4);
            assertThat(r.detectedAt()).isEqualTo(f.getDetectedAt());
            assertThat(r.weeklyApplication()).isFalse();
        });
    }

    @Test
    void liste_utilisateurIntrouvable_nomGenerique() {
        IndividualFrequencyFlagEntity f = flag();
        when(flags.findByResolvedAtIsNullAndDismissedAtIsNullOrderByDetectedAtAscIdAsc()).thenReturn(List.of(f));
        when(users.findAllById(List.of(userId))).thenReturn(List.of());

        assertThat(service.list()).singleElement()
                .satisfies(r -> assertThat(r.displayName()).isEqualTo(UserEntity.UNKNOWN_DISPLAY_NAME));
    }

    @Test
    void liste_vide_neChargeAucunUtilisateur() {
        when(flags.findByResolvedAtIsNullAndDismissedAtIsNullOrderByDetectedAtAscIdAsc()).thenReturn(List.of());

        assertThat(service.list()).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void alerteDeLaFiche_presenteSiDrapeauOuvert_absenteSinon() {
        IndividualFrequencyFlagEntity f = flag();
        when(flags.findFirstByUserIdAndResolvedAtIsNullAndDismissedAtIsNull(userId)).thenReturn(Optional.of(f));
        UUID other = UUID.randomUUID();
        when(flags.findFirstByUserIdAndResolvedAtIsNullAndDismissedAtIsNull(eq(other))).thenReturn(Optional.empty());

        IndividualFrequencyAlert alert = service.openAlertFor(userId);

        assertThat(alert).isEqualTo(new IndividualFrequencyAlert(f.getId(), 6, 30, 4, f.getDetectedAt(), false));
        assertThat(service.openAlertFor(other)).isNull();
    }
}
