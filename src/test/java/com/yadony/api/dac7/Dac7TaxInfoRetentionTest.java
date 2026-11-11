package com.yadony.api.dac7;

import com.yadony.api.auth.TaxInfoRetentionPort.Retention;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * À la suppression du compte, DAC7 allumé, le NIF reste si le voyageur, déclarable, a une opération dans l'année
 * civile courante ou la précédente (fenêtre {@code yadony.dac7.retention-years}, défaut 2). DAC7 éteint : comme avant
 * le lot, rien n'est gardé ni lu. Jamais d'exception vers la suppression.
 */
class Dac7TaxInfoRetentionTest {

    /** 10/11/2026 : fenêtre par défaut depuis le 01/01/2025. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    private final Dac7ReportReadModel readModel = mock(Dac7ReportReadModel.class);
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setCountry("FR");
        user.setTaxIdentificationNumber("3023217600053");
    }

    private Dac7TaxInfoRetention retention(boolean enabled, Integer years) {
        Dac7Properties properties = new Dac7Properties(enabled, null, years);
        return new Dac7TaxInfoRetention(new Dac7ResidencyPolicy(properties), readModel, properties, CLOCK,
                TransactionOperations.withoutTransaction());
    }

    @Test
    void declarablePayeDansLaFenetre_garde_residenceEtStatutFiges() {
        when(readModel.hasBeenPaidSince(user.getId(), Instant.parse("2025-01-01T00:00:00Z"))).thenReturn(true);
        user.setTravelerStatus(TravelerStatus.INDIVIDUAL);
        assertThat(retention(true, null).retention(user))
                .contains(new Retention("FR", TravelerStatus.INDIVIDUAL));
    }

    @Test
    void professionnel_paysDeLEntrepriseFige() {
        user.setCountry("SN");
        user.becomeProfessional("Diallo Express", "123", "BE", "2026-11-10");
        when(readModel.hasBeenPaidSince(any(), any())).thenReturn(true);
        assertThat(retention(true, null).retention(user))
                .contains(new Retention("BE", TravelerStatus.PROFESSIONAL));
    }

    @Test
    void declarableSansOperationDansLaFenetre_efface() {
        when(readModel.hasBeenPaidSince(any(), any())).thenReturn(false);
        assertThat(retention(true, null).retention(user)).isEmpty();
    }

    @Test
    void dac7Eteint_commeAvantLeLot_rienGardeRienLu() {
        assertThat(retention(false, null).retention(user)).isEmpty();
        verifyNoInteractions(readModel);
    }

    @Test
    void lectureEnEchec_jamaisDException_NifGardeParPrudence() {
        when(readModel.hasBeenPaidSince(any(), any())).thenThrow(new IllegalStateException("statement timeout"));
        Optional<Retention> decision = retention(true, null).retention(user);
        assertThat(decision).contains(new Retention("FR", null));
    }

    @Test
    void fenetreConfigurable() {
        retention(true, 3).retention(user);
        verify(readModel).hasBeenPaidSince(eq(user.getId()), eq(Instant.parse("2024-01-01T00:00:00Z")));
        // Plancher de 2 ans (lot 3b) : 1 refuse le démarrage DAC7 allumé (Dac7PropertiesTest) ; 2 = année précédente.
        retention(true, 2).retention(user);
        verify(readModel).hasBeenPaidSince(eq(user.getId()), eq(Instant.parse("2025-01-01T00:00:00Z")));
    }

    @Test
    void horsPerimetre_efface_sansLireLesPaiements() {
        user.setCountry("CI");
        assertThat(retention(true, null).retention(user)).isEmpty();
        user.setCountry(null);
        assertThat(retention(true, null).retention(user)).isEmpty();
        verify(readModel, never()).hasBeenPaidSince(any(), any());
    }

    @Test
    void aucunNif_rienAGarder() {
        user.setTaxIdentificationNumber(null);
        assertThat(retention(true, null).retention(user)).isEmpty();
        verify(readModel, never()).hasBeenPaidSince(any(), any());
    }
}
