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
        return new KycVerifiedIdentityService(kycRepository);
    }

    private KycVerificationEntity verification(KycVerificationStatus status, String sessionId) {
        KycVerificationEntity entity = new KycVerificationEntity();
        entity.setUserId(userId);
        entity.setStatus(status);
        entity.setStripeVerificationSessionId(sessionId);
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
    @DisplayName("Session aboutie : le snapshot porte nom, date de naissance et adresse")
    void mapsVerifiedOutputs() {
        when(kycRepository.findByUserId(userId))
                .thenReturn(Optional.of(verification(KycVerificationStatus.VERIFIED, "vs_1")));

        VerificationSession session = mock(VerificationSession.class);
        VerificationSession.VerifiedOutputs outputs = mock(VerificationSession.VerifiedOutputs.class);
        VerificationSession.VerifiedOutputs.Dob dob = mock(VerificationSession.VerifiedOutputs.Dob.class);
        com.stripe.model.Address address = mock(com.stripe.model.Address.class);
        when(session.getVerifiedOutputs()).thenReturn(outputs);
        when(outputs.getFirstName()).thenReturn("Awa");
        when(outputs.getLastName()).thenReturn("Diallo");
        when(outputs.getDob()).thenReturn(dob);
        when(dob.getDay()).thenReturn(12L);
        when(dob.getMonth()).thenReturn(4L);
        when(dob.getYear()).thenReturn(1990L);
        when(outputs.getAddress()).thenReturn(address);
        when(address.getLine1()).thenReturn("8 rue du Document");
        when(address.getCity()).thenReturn("Paris");
        when(address.getPostalCode()).thenReturn("75011");
        when(address.getCountry()).thenReturn("FR");

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
            assertThat(snapshot.get().hasDob()).isTrue();
            assertThat(snapshot.get().addressLine1()).isEqualTo("8 rue du Document");
            assertThat(snapshot.get().addressCity()).isEqualTo("Paris");
        }
    }
}
