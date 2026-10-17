package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.SmsService;
import com.yadony.api.notifications.TwilioVerifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Durcissement de l'OTP SMS : pays autorisés (SMS pumping), budgets globaux horaire et
 * journalier, un seul code valable à la fois, temps de réponse égal sans code en attente.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SmsOtpService — durcissement")
class SmsOtpHardeningTest {

    @Mock private SmsOtpRepository repository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SmsService smsService;
    @Mock private UserRepository userRepository;
    @Mock private FirebaseContactService firebaseContact;
    @Mock private AuditService auditService;
    @Mock private Environment environment;
    @Mock private TwilioVerifyService twilioVerify;

    private final SmsOtpProperties properties = new SmsOtpProperties();
    private SmsOtpService service;

    private static final String PHONE = "+221701234567";

    @BeforeEach
    void build() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
        lenient().when(smsService.isEnabled()).thenReturn(true);
        service = new SmsOtpService(repository, passwordEncoder, smsService, properties,
                null, userRepository, firebaseContact, auditService, environment,
                TestMessages.resolver(), twilioVerify);
    }

    @AfterEach
    void clear() {
        TestMessages.clearRequest();
    }

    @Test
    @DisplayName("pays hors liste → 422 phone-country-not-supported, aucun SMS ni ligne")
    void send_countryNotAllowed() {
        properties.setAllowedCountryCodes(List.of("33", "221"));

        assertThatThrownBy(() -> service.sendOtp("+8821612345678"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("phone-country-not-supported");
                });
        verify(repository, never()).save(any());
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("pays de la liste → envoi, ancien code invalidé avant d'enregistrer le nouveau")
    void send_allowedCountry_invalidatesPrevious() {
        properties.setAllowedCountryCodes(List.of("+221", " 33 "));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");

        service.sendOtp(PHONE);

        InOrder order = inOrder(repository);
        order.verify(repository).invalidateActive(eq(PHONE), any());
        order.verify(repository).save(any());
        verify(smsService).send(eq(PHONE), anyString());
    }

    @Test
    @DisplayName("budget horaire global atteint → 503 sms-otp-budget-exceeded")
    void send_hourlyBudgetExceeded() {
        properties.setMaxSendsPerHourGlobal(10);
        when(repository.countAllSince(any())).thenReturn(10L);

        assertThatThrownBy(() -> service.sendOtp(PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("sms-otp-budget-exceeded");
                });
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("budget journalier atteint alors que l'heure est sous la limite → 503")
    void send_dailyBudgetExceeded() {
        properties.setMaxSendsPerHourGlobal(100);
        properties.setMaxSendsPerDayGlobal(500);
        // Premier appel : dernière heure (5) ; second : dernières 24 h (500).
        when(repository.countAllSince(any())).thenReturn(5L, 500L);

        assertThatThrownBy(() -> service.sendOtp(PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("sms-otp-budget-exceeded"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("code accepté → les autres codes du numéro sont invalidés")
    void verify_success_invalidatesOthers() {
        SmsOtpEntity t = new SmsOtpEntity();
        t.setPhoneNumber(PHONE);
        t.setCodeHash("$2a$10$hash");
        t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(repository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(t));
        when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);

        service.verifyOtp(PHONE, "123456");

        assertThat(t.getUsedAt()).isNotNull();
        verify(repository).invalidateActive(eq(PHONE), any());
    }

    @Test
    @DisplayName("aucun code en attente → BCrypt factice puis 400 phone-otp-invalid")
    void verify_noPendingCode_runsDummyBcrypt() {
        when(repository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$dummy");

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "000000"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("phone-otp-invalid"));
        verify(passwordEncoder).matches("000000", "$2a$10$dummy");
    }

    @Test
    @DisplayName("SmsOtpProperties : liste vide = tous les pays ; indicatifs nettoyés et sans doublon")
    void properties_countryList() {
        assertThat(properties.isCountryAllowed("+8821612345678")).isTrue();

        properties.setAllowedCountryCodes(List.of(" +33", "33", "", "1"));
        assertThat(properties.getAllowedCountryCodes()).containsExactly("33", "1");
        assertThat(properties.isCountryAllowed("+33612345678")).isTrue();
        assertThat(properties.isCountryAllowed("+15551234567")).isTrue();
        assertThat(properties.isCountryAllowed("+221701234567")).isFalse();

        properties.setAllowedCountryCodes(null);
        assertThat(properties.getAllowedCountryCodes()).isEmpty();
    }
}
