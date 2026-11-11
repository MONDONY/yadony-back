package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Règle du propriétaire : on ne sollicite que le voyageur concerné (résidence connue ET déclarable ET déjà payé au moins
 * une fois ET NIF absent). Résidence inconnue, hors périmètre ou jamais payé : aucune demande.
 */
class Dac7SolicitationPolicyTest {

    private final Dac7ReportReadModel readModel = mock(Dac7ReportReadModel.class);
    private final Dac7SolicitationPolicy policy = new Dac7SolicitationPolicy(
            new Dac7ResidencyPolicy(new Dac7Properties(false, null)), readModel);
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    @Test
    void concerne_solliciter() {
        user.setCountry("FR");
        when(readModel.hasBeenPaid(user.getId())).thenReturn(true);
        assertThat(policy.statusOf(user)).isEqualTo(Dac7SolicitationStatus.SOLLICITER);
    }

    @Test
    void etatPartenaireConfigureMaisNonTransmisParLaDgfip_neRienDemander() {
        // Annexe 6.1 (revenus 2026) : seuls les 27 États membres sont transmis ; un partenaire configuré (SN) ne
        // serait jamais déclaré : aucune demande.
        Dac7SolicitationPolicy withPartner = new Dac7SolicitationPolicy(
                new Dac7ResidencyPolicy(new Dac7Properties(false, java.util.List.of("SN"))), readModel);
        user.setCountry("SN");
        when(readModel.hasBeenPaid(user.getId())).thenReturn(true);
        assertThat(withPartner.statusOf(user)).isEqualTo(Dac7SolicitationStatus.NE_PAS_SOLLICITER);
        verify(readModel, never()).hasBeenPaid(any());
    }

    @Test
    void horsPerimetre_neRienDemander() {
        user.setCountry("CI");
        when(readModel.hasBeenPaid(user.getId())).thenReturn(true);
        assertThat(policy.statusOf(user)).isEqualTo(Dac7SolicitationStatus.NE_PAS_SOLLICITER);
    }

    @Test
    void residenceInconnue_neRienDemander() {
        user.setCountry(null);
        when(readModel.hasBeenPaid(user.getId())).thenReturn(true);
        assertThat(policy.statusOf(user)).isEqualTo(Dac7SolicitationStatus.NE_PAS_SOLLICITER);
        user.setCountry("ZZ");
        assertThat(policy.statusOf(user)).isEqualTo(Dac7SolicitationStatus.NE_PAS_SOLLICITER);
    }

    @Test
    void jamaisPaye_neRienDemander() {
        user.setCountry("FR");
        when(readModel.hasBeenPaid(user.getId())).thenReturn(false);
        assertThat(policy.statusOf(user)).isEqualTo(Dac7SolicitationStatus.NE_PAS_SOLLICITER);
    }

    @Test
    void dejaFourni_sansLectureDesPaiements() {
        user.setCountry("FR");
        user.setTaxIdentificationNumber("3023217600053");
        assertThat(policy.statusOf(user)).isEqualTo(Dac7SolicitationStatus.DEJA_FOURNI);
        verify(readModel, never()).hasBeenPaid(any());
    }

    @Test
    void horsPerimetre_neLitPasLesPaiements() {
        user.setCountry("SN");
        policy.statusOf(user);
        verify(readModel, never()).hasBeenPaid(any());
    }

    @Test
    void regleComplete() {
        for (Dac7ResidencyStatus residency : Dac7ResidencyStatus.values()) {
            for (boolean paid : new boolean[] {true, false}) {
                assertThat(Dac7SolicitationPolicy.decide(residency, paid, true))
                        .isEqualTo(Dac7SolicitationStatus.DEJA_FOURNI);
                assertThat(Dac7SolicitationPolicy.decide(residency, paid, false))
                        .isEqualTo(residency == Dac7ResidencyStatus.DECLARABLE && paid
                                ? Dac7SolicitationStatus.SOLLICITER : Dac7SolicitationStatus.NE_PAS_SOLLICITER);
            }
        }
    }
}
