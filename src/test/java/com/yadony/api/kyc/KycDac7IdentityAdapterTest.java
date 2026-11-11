package com.yadony.api.kyc;

import com.yadony.api.dac7.Dac7VerifiedIdentity;
import com.yadony.api.kyc.provider.IdentityProviderResolver;
import com.yadony.api.kyc.provider.IdentityVerificationProvider;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KycDac7IdentityAdapterTest {

    private final KycRepository repository = mock(KycRepository.class);
    private final IdentityProviderResolver resolver = mock(IdentityProviderResolver.class);
    private final IdentityVerificationProvider provider = mock(IdentityVerificationProvider.class);
    private final KycDac7IdentityAdapter adapter = new KycDac7IdentityAdapter(repository, resolver);
    private final UUID user = UUID.randomUUID();

    private KycVerificationEntity verification(KycVerificationStatus status, String session) {
        KycVerificationEntity v = new KycVerificationEntity();
        ReflectionTestUtils.setField(v, "status", status);
        ReflectionTestUtils.setField(v, "verificationSessionId", session);
        ReflectionTestUtils.setField(v, "provider", VerificationProviderKind.DIDIT);
        return v;
    }

    @Test
    void verifie_relitChezLeFournisseurDeLaLigne() {
        when(repository.findByUserId(user)).thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "s1")));
        when(resolver.forRecord(VerificationProviderKind.DIDIT)).thenReturn(Optional.of(provider));
        when(provider.fetchVerifiedIdentity("s1")).thenReturn(Optional.of(new VerifiedIdentityDetails("Awa", "Diallo",
                LocalDate.of(1990, 1, 2), "1 rue de Paris", null, "69001", "Lyon", "FR", null)));

        var lookup = adapter.verifiedIdentity(user);
        assertThat(lookup.status()).isEqualTo(com.yadony.api.dac7.Dac7VerifiedIdentityPort.Status.FOUND);
        Dac7VerifiedIdentity identity = lookup.identity();

        assertThat(identity.birthDate()).isEqualTo(LocalDate.of(1990, 1, 2));
        assertThat(identity.address().city()).isEqualTo("Lyon");
        assertThat(identity.address().countryCode()).isEqualTo("FR");
        assertThat(identity.toString()).doesNotContain("Awa");
    }

    @Test
    void sansAdresse_adresseNulle() {
        when(repository.findByUserId(user)).thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "s1")));
        when(resolver.forRecord(VerificationProviderKind.DIDIT)).thenReturn(Optional.of(provider));
        when(provider.fetchVerifiedIdentity("s1")).thenReturn(Optional.of(new VerifiedIdentityDetails("Awa", "Diallo",
                LocalDate.of(1990, 1, 2), null, null, null, null, null, null)));
        assertThat(adapter.verifiedIdentity(user).identity().address()).isNull();
    }

    @Test
    void nonVerifieOuSansSession_rienDemandeAuFournisseur() {
        var none = com.yadony.api.dac7.Dac7VerifiedIdentityPort.Status.NONE;
        when(repository.findByUserId(user)).thenReturn(Optional.of(verification(KycVerificationStatus.PENDING, "s1")));
        assertThat(adapter.verifiedIdentity(user).status()).isEqualTo(none);
        when(repository.findByUserId(user)).thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, null)));
        assertThat(adapter.verifiedIdentity(user).status()).isEqualTo(none);
        when(repository.findByUserId(user)).thenReturn(Optional.empty());
        assertThat(adapter.verifiedIdentity(user).status()).isEqualTo(none);
        verifyNoInteractions(resolver);
    }

    @Test
    void panne_indisponible_jamaisAbsente() {
        when(repository.findByUserId(user)).thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "s1")));
        when(resolver.forRecord(VerificationProviderKind.DIDIT)).thenReturn(Optional.of(provider));
        when(provider.fetchVerifiedIdentity("s1")).thenThrow(new IdentityProviderUnavailableException("timeout"));
        assertThat(adapter.verifiedIdentity(user).status())
                .isEqualTo(com.yadony.api.dac7.Dac7VerifiedIdentityPort.Status.UNAVAILABLE);
        when(repository.findByUserId(user)).thenThrow(new IllegalStateException("db"));
        assertThat(adapter.verifiedIdentity(user).status())
                .isEqualTo(com.yadony.api.dac7.Dac7VerifiedIdentityPort.Status.UNAVAILABLE);
    }

    @Test
    void sansFournisseurDeLaLigne_aucuneIdentite() {
        when(repository.findByUserId(user)).thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "s1")));
        when(resolver.forRecord(VerificationProviderKind.DIDIT)).thenReturn(Optional.empty());
        assertThat(adapter.verifiedIdentity(user).status())
                .isEqualTo(com.yadony.api.dac7.Dac7VerifiedIdentityPort.Status.NONE);
    }
}
