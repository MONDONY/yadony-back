package com.yadony.api.kyc;

import com.stripe.model.identity.VerificationSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Le contrat central est le best-effort : quel que soit l'etat local ou la reponse Stripe,
 * cette classe rend un snapshot ou {@code empty} — jamais une exception, car un prefill ne
 * doit jamais faire echouer la creation du compte Connect qui l'appelle.
 */
@ExtendWith(MockitoExtension.class)
class KycVerifiedIdentityServiceTest {

    @Mock KycRepository kycRepository;

    private final UUID userId = UUID.randomUUID();

    private KycVerifiedIdentityService service() {
        return new KycVerifiedIdentityService(kycRepository,
                new com.yadony.api.kyc.provider.IdentityProviderResolver(
                        java.util.List.of(new com.yadony.api.kyc.provider.stripe.StripeIdentityProvider(
                                "https://yadony.com/kyc/complete", "")),
                        org.mockito.Mockito.mock(com.yadony.api.config.PlatformSettingsService.class)));
    }

    private KycVerificationEntity verification(KycVerificationStatus status, String sessionId) {
        KycVerificationEntity entity = new KycVerificationEntity();
        entity.setUserId(userId);
        entity.setStatus(status);
        entity.setVerificationSessionId(sessionId);
        return entity;
    }

    @Test
    @DisplayName("Aucune verification locale : pas de snapshot, pas d'appel Stripe")
    void emptyWhenNoLocalVerification() {
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThat(service().forUser(userId)).isEmpty();
    }

    @Test
    @DisplayName("Verification non aboutie : pas de snapshot — seule VERIFIED fait foi")
    void emptyWhenVerificationNotVerified() {
        when(kycRepository.findByUserId(userId))
                .thenReturn(Optional.of(verification(KycVerificationStatus.PENDING, "vs_1")));

        assertThat(service().forUser(userId)).isEmpty();
    }

    @Test
    @DisplayName("Echec Stripe : empty, jamais d'exception — le provisioning continue sans prefill")
    void emptyWhenStripeFails() {
        when(kycRepository.findByUserId(userId))
                .thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "vs_1")));

        try (MockedStatic<VerificationSession> sessions = mockStatic(VerificationSession.class)) {
            sessions.when(() -> VerificationSession.retrieve(
                    eq("vs_1"),
                    any(com.stripe.param.identity.VerificationSessionRetrieveParams.class),
                    any()))
                    .thenThrow(new RuntimeException("stripe down"));

            assertThat(service().forUser(userId)).isEmpty();
        }
    }

    @Test
    @DisplayName("Session aboutie : le snapshot porte le nom verifie, et rien d'autre")
    void mapsVerifiedOutputs() {
        when(kycRepository.findByUserId(userId))
                .thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "vs_1")));

        VerificationSession session = mock(VerificationSession.class);
        VerificationSession.VerifiedOutputs outputs = mock(VerificationSession.VerifiedOutputs.class);
        when(session.getVerifiedOutputs()).thenReturn(outputs);
        when(outputs.getFirstName()).thenReturn("Awa");
        when(outputs.getLastName()).thenReturn("Diallo");

        try (MockedStatic<VerificationSession> sessions = mockStatic(VerificationSession.class)) {
            sessions.when(() -> VerificationSession.retrieve(
                    eq("vs_1"),
                    any(com.stripe.param.identity.VerificationSessionRetrieveParams.class),
                    any()))
                    .thenReturn(session);

            Optional<VerifiedIdentitySnapshot> snapshot = service().forUser(userId);

            assertThat(snapshot).isPresent();
            assertThat(snapshot.get().givenName()).isEqualTo("Awa");
            assertThat(snapshot.get().surname()).isEqualTo("Diallo");
        }
    }

    @Test
    @DisplayName("Ni date de naissance ni adresse ne sont lues : Stripe Connect les demande "
            + "lui-meme, plus rien ici n'en depend")
    void neverReadsDobNorAddress() {
        when(kycRepository.findByUserId(userId))
                .thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "vs_1")));

        VerificationSession session = mock(VerificationSession.class);
        VerificationSession.VerifiedOutputs outputs = mock(VerificationSession.VerifiedOutputs.class);
        when(session.getVerifiedOutputs()).thenReturn(outputs);
        when(outputs.getFirstName()).thenReturn("Awa");

        try (MockedStatic<VerificationSession> sessions = mockStatic(VerificationSession.class)) {
            sessions.when(() -> VerificationSession.retrieve(
                    eq("vs_1"),
                    any(com.stripe.param.identity.VerificationSessionRetrieveParams.class),
                    any()))
                    .thenReturn(session);

            service().forUser(userId);

            org.mockito.Mockito.verify(outputs, org.mockito.Mockito.never()).getDob();
            org.mockito.Mockito.verify(outputs, org.mockito.Mockito.never()).getAddress();
        }
    }
}
