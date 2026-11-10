package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * À la suppression du compte, le NIF reste si le voyageur, déclarable, a une opération dans l'année civile courante
 * ou la précédente (fenêtre {@code yadony.dac7.retention-years}, défaut 2) : la déclaration de janvier en a besoin.
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

    private Dac7TaxInfoRetention retention(Integer years) {
        Dac7Properties properties = new Dac7Properties(false, null, years);
        return new Dac7TaxInfoRetention(new Dac7ResidencyPolicy(properties), readModel, properties, CLOCK);
    }

    @Test
    void declarablePayeDansLaFenetre_garde() {
        when(readModel.hasBeenPaidSince(user.getId(), Instant.parse("2025-01-01T00:00:00Z"))).thenReturn(true);
        assertThat(retention(null).mustRetainTaxInfo(user)).isTrue();
    }

    @Test
    void declarableSansOperationDansLaFenetre_efface() {
        when(readModel.hasBeenPaidSince(any(), any())).thenReturn(false);
        assertThat(retention(null).mustRetainTaxInfo(user)).isFalse();
    }

    @Test
    void fenetreConfigurable() {
        retention(3).mustRetainTaxInfo(user);
        verify(readModel).hasBeenPaidSince(eq(user.getId()), eq(Instant.parse("2024-01-01T00:00:00Z")));
        retention(1).mustRetainTaxInfo(user);
        verify(readModel).hasBeenPaidSince(eq(user.getId()), eq(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void horsPerimetre_efface_sansLireLesPaiements() {
        user.setCountry("CI");
        assertThat(retention(null).mustRetainTaxInfo(user)).isFalse();
        user.setCountry(null);
        assertThat(retention(null).mustRetainTaxInfo(user)).isFalse();
        verify(readModel, never()).hasBeenPaidSince(any(), any());
    }

    @Test
    void aucunNif_rienAGarder() {
        user.setTaxIdentificationNumber(null);
        assertThat(retention(null).mustRetainTaxInfo(user)).isFalse();
        verify(readModel, never()).hasBeenPaidSince(any(), any());
    }
}
