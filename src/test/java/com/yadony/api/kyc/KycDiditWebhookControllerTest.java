package com.yadony.api.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.provider.didit.DiditProperties;
import com.yadony.api.kyc.provider.didit.DiditWebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Un cas par ligne du tableau de correspondance des statuts Didit, plus les cas de bord :
 * signature, environnement, session inconnue, rejeu.
 */
@ExtendWith(MockitoExtension.class)
class KycDiditWebhookControllerTest {

    private static final String SESSION_ID = "sess_1";
    private static final String SIGNATURE = "sig";
    private static final String TIMESTAMP = "1760000000";

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminAlertService adminAlert;
    @Mock DiditWebhookSignatureVerifier signatureVerifier;

    private KycDiditWebhookController controller;
    private KycVerificationEntity kyc;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        // Vrai service de transition : le contrôleur ne fait que traduire, ce sont les
        // effets réels qu'on vérifie ici.
        KycStatusTransitionService transitions = new KycStatusTransitionService(
                kycRepository, userRepository, auditService, eventPublisher, adminAlert);

        controller = new KycDiditWebhookController(kycRepository, userRepository, transitions,
                signatureVerifier,
                new DiditProperties("https://verification.didit.me", "cle", "wf_1", "secret", "live"),
                new ObjectMapper());

        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setKycStatus(KycStatus.PENDING);

        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setStatus(KycVerificationStatus.PENDING);
        kyc.setVerificationSessionId(SESSION_ID);
    }

    private void signatureIsValid() {
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(true);
    }

    private void recordExists() {
        lenient().when(kycRepository.findByVerificationSessionId(SESSION_ID))
                .thenReturn(Optional.of(kyc));
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private ResponseEntity<Void> post(String body) {
        return controller.handle(body.getBytes(StandardCharsets.UTF_8), SIGNATURE, TIMESTAMP);
    }

    private static String statusPayload(String status) {
        return """
                {"webhook_type":"status.updated","session_id":"sess_1","status":"%s","environment":"live"}
                """.formatted(status);
    }

    @Test
    void approved_marksVerified() {
        signatureIsValid();
        recordExists();

        assertThat(post(statusPayload("Approved")).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_VERIFIED"), any(), anyMap());
    }

    @Test
    void declined_marksRejected_withWarningCodeAndReason() {
        signatureIsValid();
        recordExists();

        post("""
                {"webhook_type":"status.updated","session_id":"sess_1","status":"Declined",
                 "environment":"live",
                 "decision":{"warnings":[{"risk":"DOCUMENT_UNREADABLE","short_description":"Pièce illisible"}]}}
                """);

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(kyc.getRejectionCode()).isEqualTo("DOCUMENT_UNREADABLE");
        assertThat(kyc.getRejectionReason()).isEqualTo("Pièce illisible");
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        verify(adminAlert).raise(eq("KYC_IDENTITY_REJECTED"), anyString(), anyMap());
    }

    @Test
    void declined_withoutWarning_fallsBackToAGenericReason() {
        signatureIsValid();
        recordExists();

        post(statusPayload("Declined"));

        assertThat(kyc.getRejectionCode()).isEqualTo("verification_failed");
        assertThat(kyc.getRejectionReason()).isEqualTo("verification_failed");
    }

    @Test
    void inReview_keepsPending_withoutEvent() {
        signatureIsValid();
        recordExists();
        user.setKycStatus(KycStatus.NOT_STARTED);

        post(statusPayload("In Review"));

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.PENDING);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_IN_REVIEW"), any(), anyMap());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void intermediateStatuses_haveNoEffect() {
        signatureIsValid();
        recordExists();

        post(statusPayload("Not Started"));
        post(statusPayload("In Progress"));
        post(statusPayload("Awaiting User"));
        post(statusPayload("Resubmitted"));

        verify(kycRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher, adminAlert);
    }

    @Test
    void abandoned_makesTheUserAbleToStartAgain_withoutAdminAlert() {
        signatureIsValid();
        recordExists();

        post(statusPayload("Abandoned"));

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_ABANDONED"), any(), anyMap());
        verifyNoInteractions(adminAlert);
    }

    @Test
    void expired_andKycExpired_makeTheUserAbleToStartAgain() {
        signatureIsValid();
        recordExists();

        post(statusPayload("Expired"));
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);

        user.setKycStatus(KycStatus.PENDING);
        post(statusPayload("Kyc Expired"));
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);

        verify(auditService, org.mockito.Mockito.times(2))
                .log(eq("kyc_verification"), any(), eq("KYC_EXPIRED"), any(), anyMap());
    }

    @Test
    void badSignature_returns401_withoutReadingTheBody() {
        when(signatureVerifier.verify(any(), any(), any())).thenReturn(false);

        assertThat(post(statusPayload("Approved")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(kycRepository, userRepository, auditService);
    }

    @Test
    void unknownWebhookType_returns200_withoutTouchingAnything() {
        signatureIsValid();

        ResponseEntity<Void> response = post("""
                {"webhook_type":"transaction.created","session_id":"sess_1","status":"Approved","environment":"live"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(kycRepository, userRepository);
    }

    /** Un test bac à sable ne doit jamais pouvoir vérifier un compte de production. */
    @Test
    void mismatchedEnvironment_isIgnored() {
        signatureIsValid();

        ResponseEntity<Void> response = post("""
                {"webhook_type":"status.updated","session_id":"sess_1","status":"Approved","environment":"sandbox"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(kycRepository, userRepository);
    }

    @Test
    void unreadableBody_returns200_withoutTouchingAnything() {
        signatureIsValid();

        assertThat(post("pas du json").getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(kycRepository, userRepository);
    }

    @Test
    void unknownSession_returns200_withoutTouchingAnything() {
        signatureIsValid();
        when(kycRepository.findByVerificationSessionId(SESSION_ID)).thenReturn(Optional.empty());

        assertThat(post(statusPayload("Approved")).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(kycRepository, never()).save(any());
    }

    @Test
    void missingUser_returns200_withoutTouchingAnything() {
        signatureIsValid();
        when(kycRepository.findByVerificationSessionId(SESSION_ID)).thenReturn(Optional.of(kyc));
        when(userRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(post(statusPayload("Approved")).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(kycRepository, never()).save(any());
    }

    /** Didit réessaie deux fois : le même événement rejoué ne doit rien produire de plus. */
    @Test
    void replayingTheSameEvent_hasNoSecondEffect() {
        signatureIsValid();
        recordExists();

        post(statusPayload("Approved"));
        post(statusPayload("Approved"));

        verify(eventPublisher, org.mockito.Mockito.times(1))
                .publishEvent(any(com.yadony.api.kyc.events.UserKycVerifiedEvent.class));
        verify(auditService, org.mockito.Mockito.times(1))
                .log(eq("kyc_verification"), any(), eq("KYC_VERIFIED"), any(), anyMap());
    }

    /** Un événement tardif ne doit jamais rétrograder une vérification acquise. */
    @Test
    void declined_afterVerified_isIgnored() {
        signatureIsValid();
        recordExists();
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);

        post(statusPayload("Declined"));

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verifyNoInteractions(adminAlert);
    }
}
