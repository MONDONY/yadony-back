package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.SmsService;
import com.yadony.api.notifications.TwilioVerifyService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmsOtpService — comptes de revue des stores")
class SmsOtpReviewAccountsTest {

    private static final String REVIEW_PHONE = "+221773456789";
    private static final String OTHER_PHONE = "+221701234567";

    @Mock private SmsOtpRepository smsOtpRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SmsService smsService;
    @Mock private FirebaseAuth firebaseAuth;
    @Mock private UserRepository userRepository;
    @Mock private FirebaseContactService firebaseContact;
    @Mock private AuditService auditService;
    @Mock private Environment environment;
    @Mock private TwilioVerifyService twilioVerify;

    private final SmsOtpProperties properties = new SmsOtpProperties();

    private SmsOtpService newService(String profile) {
        properties.setReviewAccounts(List.of(REVIEW_PHONE + ":123456"));
        when(environment.getActiveProfiles()).thenReturn(new String[] {profile});
        return new SmsOtpService(smsOtpRepository, passwordEncoder, smsService, properties,
                firebaseAuth, userRepository, firebaseContact, auditService, environment,
                TestMessages.resolver(), twilioVerify, null);
    }

    private SmsOtpEntity pendingToken() {
        SmsOtpEntity token = new SmsOtpEntity();
        token.setPhoneNumber(REVIEW_PHONE);
        token.setCodeHash("$2a$10$review");
        token.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        return token;
    }

    @Nested
    @DisplayName("sendOtp")
    class SendOtp {

        @Test
        @DisplayName("aucun SMS ni Verify, le hash du code fixe est enregistré, même SMS coupés en prod")
        void storesFixedCodeWithoutSending() {
            SmsOtpService service = newService("prod");
            when(passwordEncoder.encode("123456")).thenReturn("$2a$10$review");

            assertThat(service.sendOtp(REVIEW_PHONE)).isNotNull();

            verify(smsOtpRepository).save(argThat(e ->
                    REVIEW_PHONE.equals(e.getPhoneNumber()) && "$2a$10$review".equals(e.getCodeHash())));
            verify(smsService, never()).send(anyString(), anyString());
            verifyNoInteractions(twilioVerify);
        }

        @Test
        @DisplayName("les limites d'envoi restent appliquées au compte de revue")
        void rateLimitStillApplies() {
            SmsOtpService service = newService("prod");
            when(smsOtpRepository.countByPhoneSince(eq(REVIEW_PHONE), any())).thenReturn(5L);

            assertThatThrownBy(() -> service.sendOtp(REVIEW_PHONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                    .isEqualTo("phone-otp-rate-limit");
            verify(smsOtpRepository, never()).save(any());
        }

        @Test
        @DisplayName("un autre numéro garde la route Twilio Verify")
        void otherNumberStillUsesVerify() {
            SmsOtpService service = newService("prod");
            when(smsService.isEnabled()).thenReturn(true);
            when(twilioVerify.handles(OTHER_PHONE)).thenReturn(true);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$random");

            service.sendOtp(OTHER_PHONE);

            verify(passwordEncoder, never()).encode("123456");
            verify(twilioVerify).start(eq(OTHER_PHONE), anyString(), any());
        }
    }

    @Nested
    @DisplayName("consumeOtp")
    class ConsumeOtp {

        @Test
        @DisplayName("le code fixe est contrôlé localement, Twilio Verify n'est pas interrogé")
        void checksFixedCodeLocally() {
            SmsOtpService service = newService("prod");
            SmsOtpEntity token = pendingToken();
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(REVIEW_PHONE))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("123456", "$2a$10$review")).thenReturn(true);

            service.consumeOtp(REVIEW_PHONE, "123456");

            assertThat(token.getUsedAt()).isNotNull();
            verifyNoInteractions(twilioVerify);
        }

        @Test
        @DisplayName("un mauvais code est refusé et compte comme tentative")
        void wrongCodeRejected() {
            SmsOtpService service = newService("prod");
            SmsOtpEntity token = pendingToken();
            when(smsOtpRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(REVIEW_PHONE))
                    .thenReturn(Optional.of(token));
            when(passwordEncoder.matches("000000", "$2a$10$review")).thenReturn(false);

            assertThatThrownBy(() -> service.consumeOtp(REVIEW_PHONE, "000000"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                    .isEqualTo("phone-otp-invalid");
            verify(smsOtpRepository).incrementAttempts(token.getId());
        }
    }

    @Nested
    @DisplayName("SmsOtpProperties.reviewCodeFor")
    class ReviewCodeFor {

        @Test
        @DisplayName("vide par défaut")
        void emptyByDefault() {
            assertThat(new SmsOtpProperties().reviewCodeFor(REVIEW_PHONE)).isEmpty();
        }

        @Test
        @DisplayName("retrouve le code du numéro listé, espaces tolérés")
        void findsListedNumber() {
            SmsOtpProperties props = new SmsOtpProperties();
            props.setReviewAccounts(List.of(" +33600000000:654321 ", REVIEW_PHONE + ":123456"));

            assertThat(props.reviewCodeFor(REVIEW_PHONE)).contains("123456");
            assertThat(props.reviewCodeFor("+33600000000")).contains("654321");
            assertThat(props.reviewCodeFor(OTHER_PHONE)).isEmpty();
        }

        @Test
        @DisplayName("écarte les entrées mal formées et une liste nulle")
        void ignoresMalformedEntries() {
            SmsOtpProperties props = new SmsOtpProperties();
            props.setReviewAccounts(List.of(REVIEW_PHONE, REVIEW_PHONE + ":12345",
                    "221773456789:123456", REVIEW_PHONE + ":abcdef", ""));
            assertThat(props.reviewCodeFor(REVIEW_PHONE)).isEmpty();

            props.setReviewAccounts(null);
            assertThat(props.reviewCodeFor(REVIEW_PHONE)).isEmpty();
        }
    }
}
