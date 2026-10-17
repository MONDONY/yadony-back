package com.yadony.api.notifications;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.FirebaseContactService.Contact;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.emailotp.ResendEmailService;
import com.yadony.api.gppro.events.GpProDecisionEvent;
import com.yadony.api.gppro.events.GpProDecisionEvent.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GpProNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @Mock private UserRepository userRepository;
    @Mock private FirebaseContactService contactService;
    @Mock private ResendEmailService emailService;

    private GpProNotificationListener listener;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new GpProNotificationListener(dispatcher, userRepository, contactService, emailService);
        lenient().when(dispatcher.messagesFor(userId)).thenReturn(TestMessages.fr());
        UserEntity user = mock(UserEntity.class);
        lenient().when(user.getFirebaseUid()).thenReturn("uid-1");
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void approvedSendsPushAndEmail() {
        when(contactService.getContact("uid-1")).thenReturn(new Contact("+33600000000", "a@b.com"));

        listener.onDecision(new GpProDecisionEvent(userId, Kind.APPROVED, null));

        verify(dispatcher).notifyUser(eq(userId), eq("Vous êtes GP Pro !"),
                eq("Votre badge GP Pro est actif sur votre profil et vos trajets."),
                eq(Map.of("type", "GP_PRO_APPROVED")));
        verify(emailService).sendGpProDecision(eq("a@b.com"), eq(Kind.APPROVED), eq(null), any());
    }

    @Test
    void noEmailWhenContactHasNoEmail() {
        when(contactService.getContact("uid-1")).thenReturn(new Contact("+33600000000", null));

        listener.onDecision(new GpProDecisionEvent(userId, Kind.REVOKED, "motif"));

        verify(dispatcher).notifyUser(eq(userId), eq("Badge GP Pro retiré"), any(),
                eq(Map.of("type", "GP_PRO_REVOKED")));
        verify(emailService, never()).sendGpProDecision(any(), any(), any(), any());
    }

    @Test
    void firebaseFailureStillSendsPush() {
        when(contactService.getContact("uid-1")).thenThrow(new IllegalStateException("firebase down"));

        assertThatCode(() -> listener.onDecision(new GpProDecisionEvent(userId, Kind.REJECTED, "x")))
                .doesNotThrowAnyException();

        verify(dispatcher).notifyUser(eq(userId), eq("Demande GP Pro refusée"), any(),
                eq(Map.of("type", "GP_PRO_REJECTED")));
        verify(emailService, never()).sendGpProDecision(any(), any(), any(), any());
    }

    @Test
    void emailFailureDoesNotThrow() {
        when(contactService.getContact("uid-1")).thenReturn(new Contact(null, "a@b.com"));
        doThrow(new RuntimeException("boom")).when(emailService).sendGpProDecision(any(), any(), any(), any());

        assertThatCode(() -> listener.onDecision(new GpProDecisionEvent(userId, Kind.APPROVED, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void gpProTypesAreAnnoncesAndAlwaysOn() {
        for (String type : new String[]{"GP_PRO_APPROVED", "GP_PRO_REJECTED", "GP_PRO_REVOKED"}) {
            assertThat(NotificationCategory.fromType(type)).isEqualTo(NotificationCategory.ANNONCE);
            assertThat(NotificationPrefsService.ALWAYS_ON).contains(type);
        }
    }
}
