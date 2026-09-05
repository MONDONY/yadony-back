package com.yadony.api.kyc.provider;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * La regle de resolution est asymetrique : creation par le reglage, relecture par la ligne.
 * C'est ce qui permet de retirer une implementation sans casser l'historique.
 */
@ExtendWith(MockitoExtension.class)
class IdentityProviderResolverTest {

    @Mock PlatformSettingsService settings;

    private final IdentityVerificationProvider stripe = new FakeProvider(VerificationProviderKind.STRIPE);
    private final IdentityVerificationProvider didit = new FakeProvider(VerificationProviderKind.DIDIT);

    private IdentityProviderResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new IdentityProviderResolver(List.of(stripe, didit), settings);
    }

    @Test
    void forCreation_followsTheSetting() {
        when(settings.kycDiditEnabled()).thenReturn(true);
        assertThat(resolver.forCreation().kind()).isEqualTo(VerificationProviderKind.DIDIT);

        when(settings.kycDiditEnabled()).thenReturn(false);
        assertThat(resolver.forCreation().kind()).isEqualTo(VerificationProviderKind.STRIPE);
    }

    @Test
    void forCreation_failsClearly_whenTheChosenProviderIsNotDeployed() {
        IdentityProviderResolver diditOnly = new IdentityProviderResolver(List.of(didit), settings);
        when(settings.kycDiditEnabled()).thenReturn(false);

        assertThatThrownBy(diditOnly::forCreation)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Impossible de créer la session");
    }

    @Test
    void forRecord_followsTheRow_neverTheSetting() {
        // Réglage sur Didit, ligne produite par Stripe : c'est Stripe qui doit répondre.
        assertThat(resolver.forRecord(VerificationProviderKind.STRIPE))
                .map(IdentityVerificationProvider::kind)
                .contains(VerificationProviderKind.STRIPE);

        assertThat(resolver.forRecord(VerificationProviderKind.DIDIT))
                .map(IdentityVerificationProvider::kind)
                .contains(VerificationProviderKind.DIDIT);
    }

    /** Le jour du retrait de Stripe, les vieilles lignes doivent se dégrader, pas exploser. */
    @Test
    void forRecord_isEmpty_whenTheImplementationIsGone() {
        IdentityProviderResolver diditOnly = new IdentityProviderResolver(List.of(didit), settings);

        assertThat(diditOnly.forRecord(VerificationProviderKind.STRIPE)).isEmpty();
    }

    @Test
    void forRecord_isEmpty_whenTheRowCarriesNoProvider() {
        assertThat(resolver.forRecord(null)).isEmpty();
    }

    private record FakeProvider(VerificationProviderKind kind) implements IdentityVerificationProvider {

        @Override
        public ProviderSession createSession(UserEntity user, String existingSessionId) {
            return new ProviderSession("https://verify.test/" + kind, "sess_" + kind);
        }

        @Override
        public void abandonSession(String providerSessionId) {
            // Rien : le resolver ne teste que l'aiguillage.
        }

        @Override
        public Optional<VerifiedIdentitySnapshot> fetchVerifiedName(String providerSessionId) {
            return Optional.empty();
        }

        @Override
        public ProviderAdminView fetchAdminView(String providerSessionId) {
            return ProviderAdminView.absent();
        }
    }
}
