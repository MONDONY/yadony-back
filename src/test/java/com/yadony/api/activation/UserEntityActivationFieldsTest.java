package com.yadony.api.activation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserEntityActivationFieldsTest {

    @Autowired UserRepository userRepository;

    @Test
    void persistsActivationFields_andDefaultsReminderCountToZero() {
        UserEntity user = userRepository.saveAndFlush(ActivationTestUsers.newUser("uid-activation-fields"));
        assertThat(user.getFirstActionReminderCount()).isZero();

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        user.setIntent("TRAVELER");
        user.setIntentDestinationCountry("SN");
        user.setIntentSource("SIGNUP");
        user.setIntentDeclaredAt(now);
        user.setKycVerifiedAt(now);
        user.setFirstActionReminderCount(1);
        user.setFirstActionReminderLastAt(now);
        userRepository.saveAndFlush(user);

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getIntent()).isEqualTo("TRAVELER");
        assertThat(reloaded.getIntentDestinationCountry()).isEqualTo("SN");
        assertThat(reloaded.getIntentSource()).isEqualTo("SIGNUP");
        assertThat(reloaded.getIntentDeclaredAt()).isEqualTo(now);
        assertThat(reloaded.getKycVerifiedAt()).isEqualTo(now);
        assertThat(reloaded.getFirstActionReminderCount()).isEqualTo(1);
        assertThat(reloaded.getFirstActionReminderLastAt()).isEqualTo(now);
    }
}
