package com.yadony.api.emailotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Durcissement de l'OTP email : adresse canonique, un seul code valable à la fois,
 * budget global d'envoi et temps de réponse égal avec ou sans code en attente.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailOtpService — durcissement")
class EmailOtpHardeningTest {

    @Mock private EmailOtpRepository repository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ResendEmailService resendEmailService;
    @Mock private UserRepository userRepository;
    @Mock private FirebaseContactService firebaseContact;
    @Mock private AuditService auditService;

    private final EmailOtpProperties properties = new EmailOtpProperties();
    private EmailOtpService service;

    @BeforeEach
    void build() {
        // FirebaseAuth absent : verifyOtp s'arrête après la consommation du code.
        service = new EmailOtpService(properties, repository, passwordEncoder, resendEmailService,
                null, userRepository, firebaseContact, auditService, TestMessages.resolver());
    }

    @AfterEach
    void clear() {
        TestMessages.clearRequest();
    }

    private static EmailOtpEntity token(String email) {
        EmailOtpEntity t = new EmailOtpEntity();
        t.setEmail(email);
        t.setCodeHash("$2a$10$hash");
        t.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        return t;
    }

    @Test
    @DisplayName("normalize : espaces retirés et minuscules, null conservé")
    void normalize() {
        assertThat(EmailOtpService.normalize("  Awa.Diop@Gmail.COM ")).isEqualTo("awa.diop@gmail.com");
        assertThat(EmailOtpService.normalize(null)).isNull();
    }

    @Test
    @DisplayName("envoi : budget compté et code enregistré sur l'adresse canonique, anciens codes invalidés avant")
    void send_canonicalEmail_invalidatesPreviousCodes() {
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");

        service.sendOtp(" Test@Example.COM");

        InOrder order = inOrder(repository);
        order.verify(repository).countByEmailSince(eq("test@example.com"), any());
        order.verify(repository).invalidateActive(eq("test@example.com"), any());
        order.verify(repository).save(org.mockito.ArgumentMatchers.argThat(
                e -> "test@example.com".equals(e.getEmail())));
        verify(resendEmailService).sendOtp(eq("test@example.com"), anyString(), any(Messages.class));
    }

    @Test
    @DisplayName("envoi : seuil global horaire dépassé → alerte seulement, l'envoi continue (pas de coupure pour tous)")
    void send_globalThresholdOnlyAlerts() {
        properties.setMaxSendsPerHourGlobal(3);
        when(repository.countAllSince(any())).thenReturn(50L);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");

        service.sendOtp("a@b.com");
        service.sendOtp("c@d.com");

        verify(resendEmailService).sendOtp(eq("a@b.com"), anyString(), any(Messages.class));
        verify(resendEmailService).sendOtp(eq("c@d.com"), anyString(), any(Messages.class));
    }

    @Test
    @DisplayName("vérification : code accepté → tous les autres codes de l'adresse sont invalidés")
    void verify_success_invalidatesOtherCodes() {
        EmailOtpEntity t = token("test@example.com");
        when(repository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc("test@example.com"))
                .thenReturn(Optional.of(t));
        when(passwordEncoder.matches("123456", "$2a$10$hash")).thenReturn(true);

        service.verifyOtp("TEST@example.com", "123456");

        assertThat(t.getUsedAt()).isNotNull();
        verify(repository).invalidateActive(eq("test@example.com"), any());
    }

    @Test
    @DisplayName("vérification sans code en attente : un BCrypt factice tourne quand même, puis 400 otp-invalid")
    void verify_noPendingCode_runsDummyBcrypt() {
        when(repository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc("x@y.com"))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$dummy");

        assertThatThrownBy(() -> service.verifyOtp("x@y.com", "000000"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("otp-invalid"));
        assertThatThrownBy(() -> service.verifyOtp("x@y.com", "111111"))
                .isInstanceOf(YadonyBusinessException.class);

        verify(passwordEncoder).matches("000000", "$2a$10$dummy");
        verify(passwordEncoder).matches("111111", "$2a$10$dummy");
        // Le hash factice n'est calculé qu'une fois.
        verify(passwordEncoder, org.mockito.Mockito.times(1)).encode(anyString());
    }

    @Test
    @DisplayName("code faux : pas d'invalidation des autres codes")
    void verify_wrongCode_doesNotInvalidate() {
        EmailOtpEntity t = token("test@example.com");
        when(repository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc("test@example.com"))
                .thenReturn(Optional.of(t));
        when(passwordEncoder.matches("999999", "$2a$10$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyOtp("test@example.com", "999999"))
                .isInstanceOf(YadonyBusinessException.class);
        verify(repository, never()).invalidateActive(anyString(), any());
    }
}
