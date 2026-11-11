package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.AdminAlertEscalator;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiditIdentityProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RETURN_URL = "https://yadony.com/kyc/complete";

    @Mock DiditClient client;
    @Mock AdminAlertEscalator alerts;

    private DiditIdentityProvider provider;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        provider = new DiditIdentityProvider(client, RETURN_URL, alerts);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    private static DiditClient.DecisionLookup read(JsonNode decision) {
        return new DiditClient.DecisionLookup(DiditClient.DecisionLookup.Outcome.READ, decision);
    }

    @Test
    void fetchVerifiedIdentity_permanentError_isUnreadableNotAnOutage() {
        when(client.lookupDecision("sess_1")).thenReturn(new DiditClient.DecisionLookup(
                DiditClient.DecisionLookup.Outcome.UNREADABLE, null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.fetchVerifiedIdentity("sess_1"))
                .isInstanceOf(com.yadony.api.kyc.IdentitySourceUnreadableException.class);
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
        when(client.createSession(eq(user.getId()), eq(RETURN_URL), eq("fr"))).thenReturn(json("""
                {"session_id":"sess_1","url":"https://verify.didit.me/fr/session/tok","status":"Not Started"}
                """));

        ProviderSession session = provider.createSession(user, null);

        assertThat(session.sessionId()).isEqualTo("sess_1");
        assertThat(session.url()).isEqualTo("https://verify.didit.me/fr/session/tok");
    }

    /** Feedback FLUTTER-27 : un utilisateur anglophone recevait l'interface Didit en français. */
    @Test
    void createSession_usesTheUserLanguage() {
        user.setPreferredLanguage(com.yadony.api.common.i18n.AppLanguage.EN);
        when(client.createSession(eq(user.getId()), eq(RETURN_URL), eq("en"))).thenReturn(json("""
                {"session_id":"sess_en","url":"https://verify.didit.me/en/session/tok","status":"Not Started"}
                """));

        assertThat(provider.createSession(user, null).sessionId()).isEqualTo("sess_en");
    }

    /** Didit dédoublonne seul : l'identifiant précédent ne change rien à l'appel. */
    @Test
    void createSession_ignoresTheExistingSessionId() {
        when(client.createSession(eq(user.getId()), eq(RETURN_URL), eq("fr"))).thenReturn(json("""
                {"session_id":"sess_2","url":"https://verify.didit.me/fr/session/tok2"}
                """));

        assertThat(provider.createSession(user, "sess_ancienne").sessionId()).isEqualTo("sess_2");
    }

    @Test
    void createSession_throwsServiceUnavailable_whenDiditFails() {
        when(client.createSession(any(), anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> provider.createSession(user, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Impossible de créer la session");
    }

    /** Réponse exacte de Didit en prod les 03 et 04/11/2026 (YADONY-BACK-PROD-D). */
    private static HttpClientErrorException noCredits() {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                """
                {"detail":"You don't have enough credits to perform this request. Please top up at https://business.didit.me"}
                """.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * YADONY-BACK-PROD-D : solde Didit à zéro, 13 vérifications refusées en deux jours sur trois
     * comptes, sans que personne ne le sache avant d'aller lire Sentry. Une alerte admin
     * (Telegram) dit quoi faire ; l'utilisateur reçoit toujours son 503.
     */
    @Test
    void createSession_raisesTheCreditsAlert_whenDiditHasNoCreditsLeft() {
        when(client.createSession(any(), anyString(), anyString())).thenThrow(noCredits());

        assertThatThrownBy(() -> provider.createSession(user, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(alerts).raiseOnce(eq(DiditIdentityProvider.CREDITS_ALERT),
                contains("business.didit.me"), anyMap());
    }

    @Test
    void createFreshSession_raisesTheCreditsAlert_whenDiditHasNoCreditsLeft() {
        when(client.createSession(any(), anyString(), anyString(), anyString())).thenThrow(noCredits());

        assertThatThrownBy(() -> provider.createFreshSession(user)).isInstanceOf(ResponseStatusException.class);

        verify(alerts).raiseOnce(eq(DiditIdentityProvider.CREDITS_ALERT), anyString(), anyMap());
    }

    @Test
    void createSession_doesNotRaiseTheCreditsAlert_onAnotherDiditError() {
        when(client.createSession(any(), anyString(), anyString())).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"callback\":[\"Enter a valid URL.\"]}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> provider.createSession(user, null)).isInstanceOf(ResponseStatusException.class);

        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    /** L'alerte ne doit jamais remplacer le 503 attendu par l'app par une 500. */
    @Test
    void createSession_stillAnswers503_whenTheAlertCannotBeRaised() {
        when(client.createSession(any(), anyString(), anyString())).thenThrow(noCredits());
        when(alerts.raiseOnce(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("base indisponible"));

        assertThatThrownBy(() -> provider.createSession(user, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Recharge faite : l'alerte se clôt seule, et la prochaine panne alertera de nouveau. */
    @Test
    void createSession_closesTheCreditsAlert_onceASessionOpensAgain() {
        when(client.createSession(eq(user.getId()), eq(RETURN_URL), eq("fr"))).thenReturn(json("""
                {"session_id":"sess_ok","url":"https://verify.didit.me/fr/session/ok"}
                """));

        provider.createSession(user, null);

        verify(alerts).resolveOpen(DiditIdentityProvider.CREDITS_ALERT);
    }

    @Test
    void createSession_returnsTheSession_evenIfClosingTheAlertFails() {
        when(client.createSession(eq(user.getId()), eq(RETURN_URL), eq("fr"))).thenReturn(json("""
                {"session_id":"sess_ok","url":"https://verify.didit.me/fr/session/ok"}
                """));
        when(alerts.resolveOpen(anyString())).thenThrow(new IllegalStateException("base indisponible"));

        assertThat(provider.createSession(user, null).sessionId()).isEqualTo("sess_ok");
    }

    @Test
    void createSession_throwsServiceUnavailable_whenTheResponseIsIncomplete() {
        when(client.createSession(any(), anyString(), anyString())).thenReturn(json("""
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
    void fetchVerifiedIdentity_readsBirthDateAndParsedAddress_forDac7() {
        when(client.lookupDecision("sess_1")).thenReturn(read(json("""
                {"status":"Approved","id_verifications":[{"first_name":"Awa","last_name":"Diallo",
                 "date_of_birth":"1990-01-02","formatted_address":"1 rue de Paris, 69001 Lyon",
                 "parsed_address":{"street_1":"1 rue de Paris","postal_code":"69001","city":"Lyon",
                                   "country_code":"fr"}}]}
                """)));

        var details = provider.fetchVerifiedIdentity("sess_1").orElseThrow();

        assertThat(details.givenName()).isEqualTo("Awa");
        assertThat(details.birthDate()).isEqualTo(java.time.LocalDate.of(1990, 1, 2));
        assertThat(details.street()).isEqualTo("1 rue de Paris");
        assertThat(details.city()).isEqualTo("Lyon");
        assertThat(details.countryCode()).isEqualTo("FR");
        assertThat(details.freeAddress()).isEqualTo("1 rue de Paris, 69001 Lyon");
        assertThat(details.toString()).doesNotContain("Awa").doesNotContain("Lyon");
    }

    @Test
    void fetchVerifiedIdentity_withoutAddressOrReadableDate_keepsWhatIsThere() {
        when(client.lookupDecision("sess_1")).thenReturn(read(json("""
                {"status":"Approved","id_verifications":[{"first_name":"Awa","date_of_birth":"02/01/1990",
                 "parsed_address":{"country":"France"}}]}
                """)));

        var details = provider.fetchVerifiedIdentity("sess_1").orElseThrow();

        assertThat(details.birthDate()).isNull();
        assertThat(details.city()).isNull();
        assertThat(details.countryCode()).isNull();
        assertThat(details.freeAddress()).isNull();
    }

    @Test
    void fetchVerifiedIdentity_decisionUnreadable_isAnOutageNotAnAbsence() {
        when(client.lookupDecision("sess_1")).thenReturn(new DiditClient.DecisionLookup(
                DiditClient.DecisionLookup.Outcome.UNAVAILABLE, null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.fetchVerifiedIdentity("sess_1"))
                .isInstanceOf(com.yadony.api.kyc.IdentityProviderUnavailableException.class);
    }

    @Test
    void fetchVerifiedIdentity_isEmpty_whenNotApprovedOrNoSession() {
        when(client.lookupDecision("sess_1")).thenReturn(read(json("""
                {"status":"Declined","id_verifications":[{"first_name":"Awa","date_of_birth":"1990-01-02"}]}
                """)));
        assertThat(provider.fetchVerifiedIdentity("sess_1")).isEmpty();
        assertThat(provider.fetchVerifiedIdentity(null)).isEmpty();
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

    /**
     * Didit dedoublonne sur vendor_data : un vendor_data distinct est le seul moyen d'obtenir une
     * nouvelle session quand l'ancienne, refusee par un administrateur, est encore inachevee chez
     * lui.
     */
    @Test
    void createFreshSession_envoieUnVendorDataDistinct() throws Exception {
        org.mockito.ArgumentCaptor<String> vendorData = org.mockito.ArgumentCaptor.forClass(String.class);
        when(client.createSession(eq(user.getId()), eq(RETURN_URL), vendorData.capture(), eq("fr"))).thenReturn(json("""
                {"session_id":"sess_fresh","url":"https://verify.didit.me/fr/session/new"}
                """));

        var session = provider.createFreshSession(user);

        assertThat(session.sessionId()).isEqualTo("sess_fresh");
        assertThat(vendorData.getValue()).startsWith(user.getId() + ":").isNotEqualTo(user.getId().toString());
    }

    @Test
    void createFreshSession_echecDidit_503() {
        when(client.createSession(any(), anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.createFreshSession(user))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
