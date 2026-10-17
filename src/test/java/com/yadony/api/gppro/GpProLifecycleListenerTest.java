package com.yadony.api.gppro;

import com.yadony.api.activation.UserIntent;
import com.yadony.api.activation.events.UserIntentDeclaredEvent;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.gppro.events.GpProStatusChangedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GpProLifecycleListenerTest {

    @Mock GpProApplicationRepository applications;
    @Mock GpProDocumentService documentService;
    @Mock UserRepository users;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;

    GpProLifecycleListener listener;
    UUID userId = UUID.randomUUID();
    UUID appId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new GpProLifecycleListener(applications, documentService, users, audit, events);
    }

    private GpProApplicationEntity application(GpProApplicationStatus status) {
        GpProApplicationEntity app = new GpProApplicationEntity();
        ReflectionTestUtils.setField(app, "id", appId);
        app.setUserId(userId);
        app.setStatus(status);
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), anyCollection()))
                .thenReturn(Optional.of(app));
        return app;
    }

    @Test
    void kycVerifiedPromotesAwaitingToPending() {
        GpProApplicationEntity app = application(GpProApplicationStatus.AWAITING_KYC);
        listener.onKycVerified(new UserKycVerifiedEvent(userId));
        assertThat(app.getStatus()).isEqualTo(GpProApplicationStatus.PENDING);
        verify(applications).save(app);
        verify(audit).log("GP_PRO_APPLICATION", appId, "GP_PRO_KYC_RELEASED", userId, Map.of());
    }

    @Test
    void kycVerifiedWithoutApplicationDoesNothing() {
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), anyCollection()))
                .thenReturn(Optional.empty());
        listener.onKycVerified(new UserKycVerifiedEvent(userId));
        verify(applications, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void intentSenderCancelsOpenApplicationAndPurges() {
        GpProApplicationEntity app = application(GpProApplicationStatus.PENDING);
        listener.onIntentDeclared(new UserIntentDeclaredEvent(userId, UserIntent.SENDER));
        assertThat(app.getStatus()).isEqualTo(GpProApplicationStatus.CANCELLED);
        verify(applications).save(app);
        verify(documentService).purgeAll(appId);
        verify(audit).log("GP_PRO_APPLICATION", appId, "GP_PRO_APPLICATION_CANCELLED", userId,
                Map.of("reason", "intent_sender"));
    }

    @Test
    void intentTravelerKeepsApplication() {
        GpProApplicationEntity app = application(GpProApplicationStatus.PENDING);
        listener.onIntentDeclared(new UserIntentDeclaredEvent(userId, UserIntent.TRAVELER));
        listener.onIntentDeclared(new UserIntentDeclaredEvent(userId, UserIntent.BOTH));
        assertThat(app.getStatus()).isEqualTo(GpProApplicationStatus.PENDING);
        verify(documentService, never()).purgeAll(any());
        verify(applications, never()).save(any());
    }

    @Test
    void accountDeletionCancelsAndPurges() {
        GpProApplicationEntity app = application(GpProApplicationStatus.AWAITING_KYC);
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));

        listener.onAccountDeletionRequested(new AccountDeletionRequestedEvent(userId));

        assertThat(app.getStatus()).isEqualTo(GpProApplicationStatus.CANCELLED);
        verify(documentService).purgeAll(appId);
        verify(audit).log("GP_PRO_APPLICATION", appId, "GP_PRO_APPLICATION_CANCELLED", userId,
                Map.of("reason", "account_deletion"));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void accountDeletionOfGpProUserPublishesStatusChangedButKeepsFlag() {
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), anyCollection()))
                .thenReturn(Optional.empty());
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setGpPro(true);
        when(users.findById(userId)).thenReturn(Optional.of(user));

        listener.onAccountDeletionRequested(new AccountDeletionRequestedEvent(userId));

        verify(events).publishEvent(new GpProStatusChangedEvent(userId, false));
        assertThat(user.isGpPro()).isTrue();
        verify(users, never()).save(any());
    }
}
