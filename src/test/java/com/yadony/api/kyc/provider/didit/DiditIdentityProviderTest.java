package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;
import com.yadony.api.kyc.provider.ProviderAdminView;
import com.yadony.api.kyc.provider.ProviderSession;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiditIdentityProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RETURN_URL = "https://yadony.com/kyc/complete";

    @Mock DiditClient client;

    private DiditIdentityProvider provider;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        provider = new DiditIdentityProvider(client, RETURN_URL);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void kind_isDidit() {
        assertThat(provider.kind()).isEqualTo(VerificationProviderKind.DIDIT);
    }

    @Test
    void createSession_mapsUrlAndSessionId() {
        when(client.createSession(eq(user.getId()), eq(RETURN_URL))).thenReturn(json("""
                {"session_id":"sess_1","url":"https://verify.didit.me/fr/session/tok","status":"Not Started"}
                """));

        ProviderSession session = provider.createSession(user, null);

        assertThat(session.sessionId()).isEqualTo("sess_1");
        assertThat(session.url()).isEqualTo("https://verify.didit.me/fr/session/tok");
    }

    /** Didit dédoublonne seul : l'identifiant précédent ne change rien à l'appel. */
    @Test
    void createSession_ignoresTheExistingSessionId() {
        when(client.createSession(eq(user.getId()), eq(RETURN_URL))).thenReturn(json("""
                {"session_id":"sess_2","url":"https://verify.didit.me/fr/session/tok2"}
                """));

        assertThat(provider.createSession(user, "sess_ancienne").sessionId()).isEqualTo("sess_2");
    }

    @Test
    void createSession_throwsServiceUnavailable_whenDiditFails() {
        when(client.createSession(any(), anyString())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> provider.createSession(user, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Impossible de créer la session");
    }

    @Test
    void createSession_throwsServiceUnavailable_whenTheResponseIsIncomplete() {
        when(client.createSession(any(), anyString())).thenReturn(json("""
                {"status":"Not Started"}
                """));

        assertThatThrownBy(() -> provider.createSession(user, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void abandonSession_doesNothing_diditHasNoCancelEndpoint() {
        provider.abandonSession("sess_1");

        verifyNoInteractions(client);
    }

    @Test
    void fetchVerifiedName_readsFirstIdVerification_whenApproved() {
        when(client.retrieveDecision("sess_1")).thenReturn(Optional.of(json("""
                {"status":"Approved","id_verifications":[{"first_name":"Awa","last_name":"Diallo"}]}
                """)));

        assertThat(provider.fetchVerifiedName("sess_1"))
                .contains(new VerifiedIdentitySnapshot("Awa", "Diallo"));
    }

    @Test
    void fetchVerifiedName_isEmpty_whenNotApproved() {
        when(client.retrieveDecision("sess_1")).thenReturn(Optional.of(json("""
                {"status":"In Review","id_verifications":[{"first_name":"Awa","last_name":"Diallo"}]}
                """)));

        assertThat(provider.fetchVerifiedName("sess_1")).isEmpty();
    }

    @Test
    void fetchVerifiedName_isEmpty_whenThereIsNoIdVerification() {
        when(client.retrieveDecision("sess_1")).thenReturn(Optional.of(json("""
                {"status":"Approved","id_verifications":[]}
                """)));

        assertThat(provider.fetchVerifiedName("sess_1")).isEmpty();
    }

    @Test
    void fetchVerifiedName_isEmpty_whenTheDecisionCannotBeRead() {
        when(client.retrieveDecision("sess_1")).thenReturn(Optional.empty());

        assertThat(provider.fetchVerifiedName("sess_1")).isEmpty();
    }

    @Test
    void fetchVerifiedName_isEmpty_whenThereIsNoSession() {
        assertThat(provider.fetchVerifiedName(null)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void fetchAdminView_readsStatusAndFirstWarning() {
        when(client.retrieveDecision("sess_1")).thenReturn(Optional.of(json("""
                {"status":"Declined","created_at":"2026-09-05T08:30:00Z",
                 "warnings":[{"risk":"DOCUMENT_UNREADABLE","short_description":"Pièce illisible"}]}
                """)));

        ProviderAdminView view = provider.fetchAdminView("sess_1");

        assertThat(view.status()).isEqualTo("Declined");
        assertThat(view.lastErrorCode()).isEqualTo("DOCUMENT_UNREADABLE");
        assertThat(view.lastErrorReason()).isEqualTo("Pièce illisible");
        assertThat(view.createdAt()).isNotNull();
        assertThat(view.unavailable()).isFalse();
    }

    @Test
    void fetchAdminView_toleratesAnUnparsableDate() {
        when(client.retrieveDecision("sess_1")).thenReturn(Optional.of(json("""
                {"status":"Approved","created_at":"pas-une-date"}
                """)));

        ProviderAdminView view = provider.fetchAdminView("sess_1");

        assertThat(view.status()).isEqualTo("Approved");
        assertThat(view.createdAt()).isNull();
        assertThat(view.unavailable()).isFalse();
    }

    @Test
    void fetchAdminView_isUnreachable_whenTheDecisionCannotBeRead() {
        when(client.retrieveDecision("sess_1")).thenReturn(Optional.empty());

        assertThat(provider.fetchAdminView("sess_1").unavailable()).isTrue();
    }

    @Test
    void fetchAdminView_isAbsent_whenThereIsNoSession() {
        ProviderAdminView view = provider.fetchAdminView(null);

        assertThat(view.unavailable()).isFalse();
        assertThat(view.status()).isNull();
        verifyNoInteractions(client);
    }
}
