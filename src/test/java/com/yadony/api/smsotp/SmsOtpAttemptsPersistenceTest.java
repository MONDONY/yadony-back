package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.notifications.SmsService;
import com.yadony.api.notifications.TwilioVerifyService;
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
@Import({SmsOtpService.class, SmsOtpProperties.class,
        SmsOtpAttemptsPersistenceTest.Encoder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("SmsOtpService — essais ratés enregistrés malgré le rollback")
class SmsOtpAttemptsPersistenceTest {

    private static final String CODE = "482913";

    @TestConfiguration
    static class Encoder {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired SmsOtpService service;
    @Autowired SmsOtpRepository repository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean SmsService smsService;
    @MockitoBean TwilioVerifyService twilioVerify;
    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean AuditService auditService;
    @MockitoBean MessagesResolver messagesResolver;
    @MockitoBean com.yadony.api.auth.PhoneOwnershipService phoneOwnership;

    private SmsOtpEntity savedToken(String phone) {
        SmsOtpEntity token = new SmsOtpEntity();
        token.setPhoneNumber(phone);
        token.setCodeHash(passwordEncoder.encode(CODE));
        token.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        return repository.save(token);
    }

    @Test
    void unCodeFauxEstDecompteEnBase() {
        String email = "+3361" + String.format("%07d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000_000));
        SmsOtpEntity token = savedToken(email);

        assertThatThrownBy(() -> service.verifyOtp(email, "000000"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("phone-otp-invalid");

        assertThat(repository.findById(token.getId()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void apresCinqEssaisLeBonCodeEstRefuse() {
        String email = "+3361" + String.format("%07d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000_000));
        savedToken(email);

        for (int i = 0; i < 5; i++) {
            String wrong = String.format("%06d", i);
            assertThatThrownBy(() -> service.verifyOtp(email, wrong))
                    .isInstanceOf(YadonyBusinessException.class);
        }

        assertThatThrownBy(() -> service.verifyOtp(email, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("phone-otp-attempts-exceeded");
    }
}
