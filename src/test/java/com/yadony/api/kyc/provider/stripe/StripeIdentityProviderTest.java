package com.yadony.api.kyc.provider.stripe;

import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;
import com.stripe.param.identity.VerificationSessionRetrieveParams;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;
import com.yadony.api.kyc.provider.ProviderAdminView;
import com.yadony.api.kyc.provider.ProviderSession;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class StripeIdentityProviderTest {

    private static final String RETURN_URL = "https://yadony.com/kyc/complete";

    private final StripeIdentityProvider provider = new StripeIdentityProvider(RETURN_URL, "");

    private static UserEntity user() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void kind_isStripe() {
        assertThat(provider.kind()).isEqualTo(VerificationProviderKind.STRIPE);
    }

    @Test
    void createSession_withoutExistingSession_createsANewOne() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession created = mock(VerificationSession.class);
            when(created.getId()).thenReturn("vs_new");
            when(created.getUrl()).thenReturn("https://verify.stripe.com/start/vs_new");
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenReturn(created);

            ProviderSession session = provider.createSession(user(), null);

            assertThat(session.sessionId()).isEqualTo("vs_new");
            assertThat(session.url()).isEqualTo("https://verify.stripe.com/start/vs_new");
        }
    }

    @Test
    void createSession_reusesAResumableSessionOfTheConfiguredFlow() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession existing = mock(VerificationSession.class);
            when(existing.getStatus()).thenReturn("requires_input");
            when(existing.getUrl()).thenReturn("https://verify.stripe.com/start/vs_old");
            vsStatic.when(() -> VerificationSession.retrieve("vs_old")).thenReturn(existing);

            ProviderSession session = provider.createSession(user(), "vs_old");

            assertThat(session.sessionId()).isEqualTo("vs_old");
            vsStatic.verify(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)), never());
        }
    }

    @Test
    void createSession_doesNotReuseATerminatedSession() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession existing = mock(VerificationSession.class);
            when(existing.getStatus()).thenReturn("verified");
            vsStatic.when(() -> VerificationSession.retrieve("vs_old")).thenReturn(existing);

            VerificationSession created = mock(VerificationSession.class);
            when(created.getId()).thenReturn("vs_new");
            when(created.getUrl()).thenReturn("https://verify.stripe.com/start/vs_new");
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenReturn(created);

            assertThat(provider.createSession(user(), "vs_old").sessionId()).isEqualTo("vs_new");
        }
    }

    @Test
    void createSession_doesNotReuseASessionOfAnotherFlow() {
        StripeIdentityProvider withFlow = new StripeIdentityProvider(RETURN_URL, "vf_current");

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession existing = mock(VerificationSession.class);
            when(existing.getStatus()).thenReturn("requires_input");
            when(existing.getVerificationFlow()).thenReturn("vf_previous");
            vsStatic.when(() -> VerificationSession.retrieve("vs_old")).thenReturn(existing);

            VerificationSession created = mock(VerificationSession.class);
            when(created.getId()).thenReturn("vs_new");
            when(created.getUrl()).thenReturn("https://verify.stripe.com/start/vs_new");
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenReturn(created);

            assertThat(withFlow.createSession(user(), "vs_old").sessionId()).isEqualTo("vs_new");
        }
    }

    @Test
    void createSession_throwsServiceUnavailable_whenStripeFails() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenThrow(new RuntimeException("Stripe unavailable"));

            assertThatThrownBy(() -> provider.createSession(user(), null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Impossible de créer la session");
        }
    }

    @Test
    void abandonSession_cancelsTheSession() throws Exception {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            vsStatic.when(() -> VerificationSession.retrieve("vs_1")).thenReturn(session);

            provider.abandonSession("vs_1");

            org.mockito.Mockito.verify(session).cancel();
        }
    }

    @Test
    void abandonSession_neverThrows_whenStripeFails() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            vsStatic.when(() -> VerificationSession.retrieve("vs_1"))
                    .thenThrow(new RuntimeException("Stripe down"));

            assertThatCode(() -> provider.abandonSession("vs_1")).doesNotThrowAnyException();
        }
    }

    @Test
    void fetchVerifiedName_readsFirstAndLastName() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession.VerifiedOutputs outputs = mock(VerificationSession.VerifiedOutputs.class);
            when(outputs.getFirstName()).thenReturn("Awa");
            when(outputs.getLastName()).thenReturn("Diallo");
            VerificationSession session = mock(VerificationSession.class);
            when(session.getVerifiedOutputs()).thenReturn(outputs);
            vsStatic.when(() -> VerificationSession.retrieve(
                            org.mockito.ArgumentMatchers.eq("vs_1"),
                            any(VerificationSessionRetrieveParams.class),
                            isNull()))
                    .thenReturn(session);

            assertThat(provider.fetchVerifiedName("vs_1"))
                    .contains(new VerifiedIdentitySnapshot("Awa", "Diallo"));
        }
    }

    @Test
    void fetchVerifiedName_isEmpty_whenOutputsAreAbsent() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            when(session.getVerifiedOutputs()).thenReturn(null);
            vsStatic.when(() -> VerificationSession.retrieve(
                            org.mockito.ArgumentMatchers.eq("vs_1"),
                            any(VerificationSessionRetrieveParams.class),
                            isNull()))
                    .thenReturn(session);

            assertThat(provider.fetchVerifiedName("vs_1")).isEmpty();
        }
    }

    @Test
    void fetchVerifiedName_isEmpty_whenStripeFails() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            vsStatic.when(() -> VerificationSession.retrieve(
                            org.mockito.ArgumentMatchers.eq("vs_1"),
                            any(VerificationSessionRetrieveParams.class),
                            isNull()))
                    .thenThrow(new RuntimeException("Stripe down"));

            assertThat(provider.fetchVerifiedName("vs_1")).isEmpty();
        }
    }

    @Test
    void fetchVerifiedName_isEmpty_whenThereIsNoSession() {
        assertThat(provider.fetchVerifiedName(null)).isEmpty();
    }

    @Test
    void fetchAdminView_readsStatusAndLastError() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession.LastError lastError = mock(VerificationSession.LastError.class);
            when(lastError.getCode()).thenReturn("document_unverified_other");
            when(lastError.getReason()).thenReturn("Pièce illisible");
            VerificationSession session = mock(VerificationSession.class);
            when(session.getStatus()).thenReturn("requires_input");
            when(session.getLastError()).thenReturn(lastError);
            when(session.getCreated()).thenReturn(1_760_000_000L);
            vsStatic.when(() -> VerificationSession.retrieve("vs_1")).thenReturn(session);

            ProviderAdminView view = provider.fetchAdminView("vs_1");

            assertThat(view.status()).isEqualTo("requires_input");
            assertThat(view.lastErrorCode()).isEqualTo("document_unverified_other");
            assertThat(view.lastErrorReason()).isEqualTo("Pièce illisible");
            assertThat(view.createdAt()).isNotNull();
            assertThat(view.unavailable()).isFalse();
        }
    }

    @Test
    void fetchAdminView_isUnreachable_whenStripeFails() {
        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            vsStatic.when(() -> VerificationSession.retrieve("vs_1"))
                    .thenThrow(new RuntimeException("Stripe down"));

            assertThat(provider.fetchAdminView("vs_1").unavailable()).isTrue();
        }
    }

    /** Aucune session a interroger n'est pas une indisponibilite Stripe. */
    @Test
    void fetchAdminView_isAbsent_whenThereIsNoSession() {
        Optional<ProviderAdminView> view = Optional.of(provider.fetchAdminView(null));

        assertThat(view).get().extracting(ProviderAdminView::unavailable).isEqualTo(false);
        assertThat(view.get().status()).isNull();
    }
}
