package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.net.ApiResource;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycStripeWebhookHandlerTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    ObjectMapper objectMapper = new ObjectMapper();
    KycStripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new KycStripeWebhookHandler(kycRepository, userRepository,
                auditService, eventPublisher, objectMapper);
    }

    private Event buildEvent(String type, String sessionId) {
        String json = String.format(
            "{\"id\":\"evt_kyc\",\"object\":\"event\",\"type\":\"%s\"," +
            "\"data\":{\"object\":{\"id\":\"%s\"}}}", type, sessionId);
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    @Test
    void supports_returnsTrue_forVerifiedEvent() {
        assertThat(handler.supports("identity.verification_session.verified")).isTrue();
        assertThat(handler.supports("payment_intent.succeeded")).isFalse();
    }

    @Test
    void supports_returnsTrue_forAllKycEventTypes() {
        assertThat(handler.supports("identity.verification_session.verified")).isTrue();
        assertThat(handler.supports("identity.verification_session.requires_input")).isTrue();
        assertThat(handler.supports("identity.verification_session.canceled")).isTrue();
    }

    @Test
    void handle_verified_setsStatusAndPublishesEvent() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_001")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(buildEvent("identity.verification_session.verified", "vs_001"));

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verify(kycRepository).save(kyc);
        verify(userRepository).save(user);
        verify(eventPublisher).publishEvent(any(com.yadony.api.kyc.events.UserKycVerifiedEvent.class));
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_VERIFIED"), any(), any());
    }

    @Test
    void handle_verified_isIdempotent_whenAlreadyVerified() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.VERIFIED);

        when(kycRepository.findByStripeVerificationSessionId("vs_002")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(buildEvent("identity.verification_session.verified", "vs_002"));

        verify(kycRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void handle_requiresInput_setsRejected() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_003")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(buildEvent("identity.verification_session.requires_input", "vs_003"));

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(kyc.getRejectionReason()).isEqualTo("verification_failed");
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_REJECTED"), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void handle_canceled_resetsToNotStarted() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_004")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(buildEvent("identity.verification_session.canceled", "vs_004"));

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(kyc.getRejectionReason()).isEqualTo("session_canceled");
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_CANCELED"), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void handle_noKycRecord_returnsEarlyWithoutSave() {
        when(kycRepository.findByStripeVerificationSessionId("vs_unknown")).thenReturn(Optional.empty());

        handler.handle(buildEvent("identity.verification_session.verified", "vs_unknown"));

        verify(kycRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void handle_noUser_returnsEarlyWithoutSave() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_005")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        handler.handle(buildEvent("identity.verification_session.verified", "vs_005"));

        verify(kycRepository, never()).save(any());
    }

    @Test
    void handle_canceled_isIdempotent_whenAlreadyVerified() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.VERIFIED);

        when(kycRepository.findByStripeVerificationSessionId("vs_004")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(buildEvent("identity.verification_session.canceled", "vs_004"));

        verify(kycRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void handle_requiresInput_isIdempotent_whenAlreadyVerified() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.VERIFIED);

        when(kycRepository.findByStripeVerificationSessionId("vs_006")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(buildEvent("identity.verification_session.requires_input", "vs_006"));

        verify(kycRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void handle_requiresInput_withLastErrorReason_usesReason() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_007")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Event with last_error.reason populated
        String json = "{\"id\":\"evt_kyc7\",\"object\":\"event\"," +
                "\"type\":\"identity.verification_session.requires_input\"," +
                "\"data\":{\"object\":{\"id\":\"vs_007\",\"last_error\":{\"reason\":\"document_expired\"}}}}";
        com.stripe.model.Event event = com.stripe.net.ApiResource.GSON.fromJson(json, com.stripe.model.Event.class);

        handler.handle(event);

        assertThat(kyc.getRejectionReason()).isEqualTo("document_expired");
        verify(kycRepository).save(kyc);
    }

    @Test
    void handle_requiresInput_withLastErrorCode_setsRejectionCode() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_011")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        String json = "{\"id\":\"evt_kyc11\",\"object\":\"event\"," +
                "\"type\":\"identity.verification_session.requires_input\"," +
                "\"data\":{\"object\":{\"id\":\"vs_011\",\"last_error\":" +
                "{\"code\":\"document_unverified_other\",\"reason\":\"The document is invalid.\"}}}}";
        com.stripe.model.Event event = com.stripe.net.ApiResource.GSON.fromJson(json, com.stripe.model.Event.class);

        handler.handle(event);

        assertThat(kyc.getRejectionCode()).isEqualTo("document_unverified_other");
        assertThat(kyc.getRejectionReason()).isEqualTo("The document is invalid.");
    }

    @Test
    void handle_canceled_setsRejectionCode() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_012")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(buildEvent("identity.verification_session.canceled", "vs_012"));

        assertThat(kyc.getRejectionCode()).isEqualTo("session_canceled");
    }

    @Test
    void handle_missingSessionId_returnsEarlyWithoutLookup() {
        // Event with null/missing id in the data object
        String json = "{\"id\":\"evt_kyc8\",\"object\":\"event\"," +
                "\"type\":\"identity.verification_session.verified\"," +
                "\"data\":{\"object\":{}}}";
        com.stripe.model.Event event = com.stripe.net.ApiResource.GSON.fromJson(json, com.stripe.model.Event.class);

        handler.handle(event);

        verify(kycRepository, never()).findByStripeVerificationSessionId(any());
        verify(kycRepository, never()).save(any());
    }

    @Test
    void handle_nullSessionId_returnsEarlyWithoutLookup() {
        // Event with explicit null id
        String json = "{\"id\":\"evt_kyc9\",\"object\":\"event\"," +
                "\"type\":\"identity.verification_session.verified\"," +
                "\"data\":{\"object\":{\"id\":null}}}";
        com.stripe.model.Event event = com.stripe.net.ApiResource.GSON.fromJson(json, com.stripe.model.Event.class);

        handler.handle(event);

        verify(kycRepository, never()).findByStripeVerificationSessionId(any());
    }

    @Test
    void handle_withNullLastError_doesNotSetLastErrorReason() {
        UUID userId = UUID.randomUUID();
        var kyc = new KycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(KycVerificationStatus.PENDING);
        var user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);

        when(kycRepository.findByStripeVerificationSessionId("vs_010")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Event with last_error: null (explicit JSON null)
        String json = "{\"id\":\"evt_kyc10\",\"object\":\"event\"," +
                "\"type\":\"identity.verification_session.requires_input\"," +
                "\"data\":{\"object\":{\"id\":\"vs_010\",\"last_error\":null}}}";
        com.stripe.model.Event event = com.stripe.net.ApiResource.GSON.fromJson(json, com.stripe.model.Event.class);

        handler.handle(event);

        // With null last_error, should use default "verification_failed"
        assertThat(kyc.getRejectionReason()).isEqualTo("verification_failed");
        verify(kycRepository).save(kyc);
    }
}
