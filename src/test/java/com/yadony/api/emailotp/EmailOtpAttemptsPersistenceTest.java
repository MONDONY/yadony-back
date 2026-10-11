package com.yadony.api.emailotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.MessagesResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Régression : le compteur d'essais doit survivre au rollback de la vérification.
 *
 * <p>Les tests unitaires du service lisent l'entité en mémoire ; ils ne voyaient pas que
 * l'exception « code invalide » annulait la transaction, incrément compris. Ici le service
 * passe par son proxy transactionnel, sans transaction de test autour, et on relit la base.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({EmailOtpService.class, EmailOtpProperties.class,
        EmailOtpAttemptsPersistenceTest.Encoder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("EmailOtpService — essais ratés enregistrés malgré le rollback")
class EmailOtpAttemptsPersistenceTest {

    private static final String CODE = "482913";

    @TestConfiguration
    static class Encoder {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired EmailOtpService service;
    @Autowired EmailOtpRepository repository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean ResendEmailService resendEmailService;
    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean AuditService auditService;
    @MockitoBean MessagesResolver messagesResolver;

    private EmailOtpEntity savedToken(String email) {
        EmailOtpEntity token = new EmailOtpEntity();
        token.setEmail(email);
        token.setCodeHash(passwordEncoder.encode(CODE));
        token.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        return repository.save(token);
    }

    @Test
    void unCodeFauxEstDecompteEnBase() {
        String email = "brute-" + UUID.randomUUID() + "@example.com";
        EmailOtpEntity token = savedToken(email);

        assertThatThrownBy(() -> service.verifyOtp(email, "000000"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("otp-invalid");

        assertThat(repository.findById(token.getId()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void apresCinqEssaisLeBonCodeEstRefuse() {
        String email = "brute-" + UUID.randomUUID() + "@example.com";
        savedToken(email);

        for (int i = 0; i < 5; i++) {
            String wrong = String.format("%06d", i);
            assertThatThrownBy(() -> service.verifyOtp(email, wrong))
                    .isInstanceOf(YadonyBusinessException.class);
        }

        assertThatThrownBy(() -> service.verifyOtp(email, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("otp-attempts-exceeded");
    }
}
