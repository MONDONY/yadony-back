package com.yadony.api.notifications;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.emailotp.ResendEmailService;
import com.yadony.api.notifications.NotificationEmailPolicy.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEmailServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final FirebaseContactService contacts = mock(FirebaseContactService.class);
    private final NotificationPrefsService prefs = mock(NotificationPrefsService.class);
    private final ResendEmailService resend = mock(ResendEmailService.class);
    private final MessagesResolver messages = mock(MessagesResolver.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();

    private NotificationEmailService service(boolean enabled) {
        return new NotificationEmailService(enabled, userRepository, contacts, prefs, resend, messages, clock);
    }

    @BeforeEach
    void setUp() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setFirebaseUid("uid-awa");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(contacts.getContact("uid-awa")).thenReturn(new FirebaseContactService.Contact(null, "awa@exemple.com"));
        when(prefs.isEmailAllowed(any(), any())).thenReturn(true);
        when(messages.forUser(userId)).thenReturn(TestMessages.fr());
    }

    @Test
    void receipt_isSentWithTheNotificationText() {
        service(true).send(userId, "PAYMENT_RELEASED", "Paiement versé", "Vos gains arrivent.", Map.of());

        verify(resend).sendNotification(eq("awa@exemple.com"), eq(Kind.RECEIPT), eq("PAYMENT_RELEASED"),
                eq("Paiement versé"), eq("Vos gains arrivent."), any());
    }

    @Test
    void typeWithoutEmail_orDisabled_orNoAddress_sendsNothing() {
        service(true).send(userId, "NEW_MESSAGE", "Nouveau message", "Salut", Map.of());
        service(false).send(userId, "PAYMENT_RELEASED", "t", "b", Map.of());
        when(contacts.getContact("uid-awa")).thenReturn(new FirebaseContactService.Contact("+33600000000", null));
        service(true).send(userId, "PAYMENT_RELEASED", "t", "b", Map.of());

        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void familyTurnedOff_sendsNothing() {
        when(prefs.isEmailAllowed(userId, Kind.RECAP)).thenReturn(false);

        service(true).send(userId, "BID_ACCEPTED", "Réservation acceptée", "b", Map.of());

        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    /** Au-delà du plafond, les familles coupables restent dans l'app ; un reçu part toujours. */
    @Test
    void dailyCap_appliesToOptionalFamiliesOnly() {
        NotificationEmailService service = service(true);
        for (int i = 0; i < NotificationEmailService.DAILY_CAP + 2; i++) {
            service.send(userId, "BID_ACCEPTED", "Réservation acceptée", "b", Map.of());
            service.send(userId, "PAYMENT_RELEASED", "Paiement versé", "b", Map.of());
        }

        verify(resend, times(NotificationEmailService.DAILY_CAP))
                .sendNotification(anyString(), eq(Kind.RECAP), anyString(), anyString(), anyString(), any());
        verify(resend, times(NotificationEmailService.DAILY_CAP + 2))
                .sendNotification(anyString(), eq(Kind.RECEIPT), anyString(), anyString(), anyString(), any());
    }

    @Test
    void replacesPush_onlyWhenTheEmailWillReallyLeave() {
        Map<String, String> digest = Map.of("digest", "true");

        assertThat(service(true).replacesPush(userId, "FIRST_ACTION_REMINDER", Map.of())).isTrue();
        assertThat(service(true).replacesPush(userId, "CORRIDOR_ALERT", digest)).isTrue();
        assertThat(service(true).replacesPush(userId, "PAYMENT_RELEASED", Map.of())).isFalse();
        assertThat(service(false).replacesPush(userId, "FIRST_ACTION_REMINDER", Map.of())).isFalse();

        when(prefs.isEmailAllowed(userId, Kind.TIP)).thenReturn(false);
        assertThat(service(true).replacesPush(userId, "FIRST_ACTION_REMINDER", Map.of())).isFalse();

        when(contacts.getContact("uid-awa")).thenReturn(new FirebaseContactService.Contact(null, " "));
        assertThat(service(true).replacesPush(userId, "FLIGHT_PRICE_ALERT", Map.of())).isFalse();
    }

    @Test
    void failures_areSwallowed_andKeepThePush() {
        when(userRepository.findById(userId)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service(true).send(userId, "PAYMENT_RELEASED", "t", "b", Map.of()))
                .doesNotThrowAnyException();
        assertThat(service(true).replacesPush(userId, "FIRST_ACTION_REMINDER", Map.of())).isFalse();
    }
}
