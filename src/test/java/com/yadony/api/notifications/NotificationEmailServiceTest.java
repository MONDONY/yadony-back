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
import static org.mockito.ArgumentMatchers.nullable;
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
                eq("Paiement versé"), eq("Vos gains arrivent."),
                eq("https://yadony.com/ouvrir?type=PAYMENT_RELEASED"), any());
    }

    @Test
    void button_opensTheScreenOfTheNotification() {
        UUID bidId = UUID.randomUUID();

        service(true).send(userId, "BID_ACCEPTED", "Réservation acceptée", "b", Map.of("bidId", bidId.toString()));

        verify(resend).sendNotification(eq("awa@exemple.com"), eq(Kind.RECAP), eq("BID_ACCEPTED"),
                eq("Réservation acceptée"), eq("b"),
                eq("https://yadony.com/ouvrir?type=BID_ACCEPTED&bidId=" + bidId), any());
    }

    @Test
    void typeWithoutEmail_orDisabled_orNoAddress_sendsNothing() {
        service(true).send(userId, "NEW_MESSAGE", "Nouveau message", "Salut", Map.of());
        service(false).send(userId, "PAYMENT_RELEASED", "t", "b", Map.of());
        when(contacts.getContact("uid-awa")).thenReturn(new FirebaseContactService.Contact("+33600000000", null));
        service(true).send(userId, "PAYMENT_RELEASED", "t", "b", Map.of());

        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(), nullable(String.class), any());
    }

    @Test
    void familyTurnedOff_sendsNothing() {
        when(prefs.isEmailAllowed(userId, Kind.RECAP)).thenReturn(false);

        service(true).send(userId, "BID_ACCEPTED", "Réservation acceptée", "b", Map.of());

        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(), nullable(String.class), any());
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
                .sendNotification(anyString(), eq(Kind.RECAP), anyString(), anyString(), anyString(), nullable(String.class), any());
        verify(resend, times(NotificationEmailService.DAILY_CAP + 2))
                .sendNotification(anyString(), eq(Kind.RECEIPT), anyString(), anyString(), anyString(), nullable(String.class), any());
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

    @Test
    void typeWithItsOwnLink_sendsItsButton() {
        NotificationEmailLink.Cta cta = new NotificationEmailLink.Cta("https://api/resume?t=x", "label", "detail");
        NotificationEmailLink link = link("STRIPE_ONBOARDING_INCOMPLETE", userId -> Optional.of(cta));

        withLinks(link).send(userId, "STRIPE_ONBOARDING_INCOMPLETE", "Finalisez vos paiements", "b", Map.of());

        verify(resend).sendNotification(eq("awa@exemple.com"), eq(Kind.ACCOUNT), eq("STRIPE_ONBOARDING_INCOMPLETE"),
                eq("Finalisez vos paiements"), eq("b"), eq(cta), any());
    }

    @Test
    void linkWithoutButton_orFailing_keepsTheDefaultButton() {
        withLinks(link("STRIPE_ONBOARDING_INCOMPLETE", userId -> Optional.empty()))
                .send(userId, "STRIPE_ONBOARDING_INCOMPLETE", "t", "b", Map.of());
        withLinks(link("STRIPE_ONBOARDING_INCOMPLETE", userId -> { throw new IllegalStateException("down"); }))
                .send(userId, "STRIPE_ONBOARDING_INCOMPLETE", "t", "b", Map.of());
        // Un lien d'un autre type ne s'applique pas.
        withLinks(link("FLIGHT_PRICE_ALERT", userId -> { throw new AssertionError("jamais appelé"); }))
                .send(userId, "STRIPE_ONBOARDING_INCOMPLETE", "t", "b", Map.of());

        verify(resend, times(3)).sendNotification(eq("awa@exemple.com"), eq(Kind.ACCOUNT),
                eq("STRIPE_ONBOARDING_INCOMPLETE"), eq("t"), eq("b"),
                eq("https://yadony.com/ouvrir?type=STRIPE_ONBOARDING_INCOMPLETE"),
                any(com.yadony.api.common.i18n.Messages.class));
        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(),
                any(NotificationEmailLink.Cta.class), any());
    }

    @Test
    void springConstructor_collectsTheLinks() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<NotificationEmailLink> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        NotificationEmailLink.Cta cta = new NotificationEmailLink.Cta("u", "l", "d");
        when(provider.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.of(
                link("STRIPE_ONBOARDING_INCOMPLETE", id -> Optional.of(cta))));

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<SearchSuggestionUnsubscribeLinks> unsubscribe =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<KycReminderUnsubscribeLinks> kycUnsubscribe =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        KycReminderUnsubscribeLinks kycLinks = mock(KycReminderUnsubscribeLinks.class);
        when(kycUnsubscribe.getIfAvailable()).thenReturn(kycLinks);
        when(kycLinks.urlFor(userId)).thenReturn(Optional.of(KYC_UNSUBSCRIBE));
        NotificationEmailService service = new NotificationEmailService(true, userRepository, contacts, prefs,
                resend, messages, provider, unsubscribe, kycUnsubscribe);
        service.send(userId, "STRIPE_ONBOARDING_INCOMPLETE", "t", "b", Map.of());

        verify(resend).sendNotification(anyString(), any(), anyString(), anyString(), anyString(), eq(cta), any());

        // Le lien de désinscription des relances est bien branché par le constructeur Spring.
        service.send(userId, "KYC_ACTION_REQUIRED", "t", "b", RELANCE);
        verify(resend).sendNotification(anyString(), eq(Kind.REMINDER), anyString(), anyString(), anyString(),
                nullable(String.class), eq(KYC_UNSUBSCRIBE), any());
    }

    @Test
    void searchSuggestion_carriesTheOneClickUnsubscribeLink() {
        SearchSuggestionUnsubscribeLinks links = mock(SearchSuggestionUnsubscribeLinks.class);
        when(links.urlFor(userId)).thenReturn(Optional.of("https://api.yadony.com/api/v1/public/x?t=abc"));
        UUID tripId = UUID.randomUUID();

        new NotificationEmailService(true, userRepository, contacts, prefs, resend, messages,
                java.util.List.of(), links, clock)
                .send(userId, "SEARCH_SUGGESTION", "Le trajet que vous cherchiez", "b",
                        Map.of("announcementId", tripId.toString()));

        verify(resend).sendNotification(eq("awa@exemple.com"), eq(Kind.SUGGESTION), eq("SEARCH_SUGGESTION"),
                eq("Le trajet que vous cherchiez"), eq("b"),
                eq("https://yadony.com/ouvrir?type=SEARCH_SUGGESTION&announcementId=" + tripId),
                eq("https://api.yadony.com/api/v1/public/x?t=abc"), any());
    }

    @Test
    void searchSuggestion_withoutUnsubscribeLink_stillSendsTheOrdinaryEmail() {
        SearchSuggestionUnsubscribeLinks links = mock(SearchSuggestionUnsubscribeLinks.class);
        when(links.urlFor(userId)).thenThrow(new IllegalStateException("boom"));

        new NotificationEmailService(true, userRepository, contacts, prefs, resend, messages,
                java.util.List.of(), links, clock)
                .send(userId, "SEARCH_SUGGESTION", "t", "b", Map.of());

        verify(resend).sendNotification(eq("awa@exemple.com"), eq(Kind.SUGGESTION), eq("SEARCH_SUGGESTION"),
                eq("t"), eq("b"), eq("https://yadony.com/ouvrir?type=SEARCH_SUGGESTION"), any());
    }

    @Test
    void otherFamilies_neverGetAnUnsubscribeLink() {
        SearchSuggestionUnsubscribeLinks links = mock(SearchSuggestionUnsubscribeLinks.class);

        new NotificationEmailService(true, userRepository, contacts, prefs, resend, messages,
                java.util.List.of(), links, clock)
                .send(userId, "PAYMENT_RELEASED", "t", "b", Map.of());

        verify(links, never()).urlFor(any());
        verify(resend).sendNotification(eq("awa@exemple.com"), eq(Kind.RECEIPT), eq("PAYMENT_RELEASED"),
                eq("t"), eq("b"), eq("https://yadony.com/ouvrir?type=PAYMENT_RELEASED"), any());
    }

    // ── Relance de vérification d'identité ───────────────────────────────────

    private static final Map<String, String> RELANCE =
            Map.of("type", "KYC_ACTION_REQUIRED", "reminder", "true", "reminderNumber", "2");
    private static final String KYC_UNSUBSCRIBE =
            "https://api.yadony.com/api/v1/public/notifications/kyc-reminders/unsubscribe?t=abc";

    private NotificationEmailService withKycLinks(KycReminderUnsubscribeLinks kycLinks) {
        return new NotificationEmailService(true, userRepository, contacts, prefs, resend, messages,
                java.util.List.of(), mock(SearchSuggestionUnsubscribeLinks.class), kycLinks, clock);
    }

    @Test
    void email_relance_coupeeParEmailAlertsTips() {
        KycReminderUnsubscribeLinks kycLinks = mock(KycReminderUnsubscribeLinks.class);
        when(prefs.isEmailAllowed(userId, Kind.REMINDER)).thenReturn(false);

        withKycLinks(kycLinks).send(userId, "KYC_ACTION_REQUIRED", "Votre vérification vous attend", "b", RELANCE);

        verify(prefs).isEmailAllowed(userId, Kind.REMINDER);
        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), any());
        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(),
                nullable(String.class), any());
    }

    @Test
    void email_relance_porteListUnsubscribe() {
        KycReminderUnsubscribeLinks kycLinks = mock(KycReminderUnsubscribeLinks.class);
        when(kycLinks.urlFor(userId)).thenReturn(Optional.of(KYC_UNSUBSCRIBE));

        withKycLinks(kycLinks).send(userId, "KYC_ACTION_REQUIRED", "Votre vérification vous attend", "b", RELANCE);

        // Type effectif pour la ligne propre de l'e-mail ; le bouton garde le type que l'app ouvre.
        verify(resend).sendNotification(eq("awa@exemple.com"), eq(Kind.REMINDER), eq("KYC_VERIFICATION_REMINDER"),
                eq("Votre vérification vous attend"), eq("b"),
                eq("https://yadony.com/ouvrir?type=KYC_ACTION_REQUIRED"), eq(KYC_UNSUBSCRIBE), any());
    }

    /** Une relance compte dans le plafond de 6 e-mails coupables par jour. */
    @Test
    void email_relance_compteDansLePlafond() {
        NotificationEmailService service = withKycLinks(mock(KycReminderUnsubscribeLinks.class));
        for (int i = 0; i < NotificationEmailService.DAILY_CAP + 1; i++) {
            service.send(userId, "KYC_ACTION_REQUIRED", "t", "b", RELANCE);
        }

        verify(resend, times(NotificationEmailService.DAILY_CAP)).sendNotification(anyString(), eq(Kind.REMINDER),
                anyString(), anyString(), anyString(), nullable(String.class), any());
    }

    @Test
    void email_vraiRefus_sansDesinscription() {
        KycReminderUnsubscribeLinks kycLinks = mock(KycReminderUnsubscribeLinks.class);
        when(prefs.isEmailAllowed(userId, Kind.REMINDER)).thenReturn(false);

        NotificationEmailService service = withKycLinks(kycLinks);
        for (int i = 0; i < NotificationEmailService.DAILY_CAP + 1; i++) {
            service.send(userId, "KYC_ACTION_REQUIRED", "Vérification à reprendre", "b",
                    Map.of("type", "KYC_ACTION_REQUIRED"));
        }

        // Décision : jamais coupée, jamais plafonnée, jamais de lien de désinscription.
        verify(kycLinks, never()).urlFor(any());
        verify(resend, times(NotificationEmailService.DAILY_CAP + 1)).sendNotification(eq("awa@exemple.com"),
                eq(Kind.DECISION), eq("KYC_ACTION_REQUIRED"), eq("Vérification à reprendre"), eq("b"),
                eq("https://yadony.com/ouvrir?type=KYC_ACTION_REQUIRED"), any());
        verify(resend, never()).sendNotification(anyString(), any(), anyString(), anyString(), anyString(),
                nullable(String.class), anyString(), any());
    }

    private NotificationEmailService withLinks(NotificationEmailLink... links) {
        return new NotificationEmailService(true, userRepository, contacts, prefs, resend, messages,
                java.util.List.of(links), clock);
    }

    private static NotificationEmailLink link(String type,
                                              java.util.function.Function<UUID, Optional<NotificationEmailLink.Cta>> cta) {
        return new NotificationEmailLink() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Optional<Cta> ctaFor(UUID userId) {
                return cta.apply(userId);
            }
        };
    }
}
