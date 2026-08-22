package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class UserEntityResidenceTest {

    @Test
    void residenceFieldsDefaultToNull() {
        UserEntity user = new UserEntity();
        assertThat(user.getResidenceStreet()).isNull();
        assertThat(user.getResidenceLine2()).isNull();
        assertThat(user.getResidencePostalCode()).isNull();
        assertThat(user.getOnboardingSeenAt()).isNull();
    }

    @Test
    void residenceFieldsRoundTrip() {
        UserEntity user = new UserEntity();
        Instant seen = Instant.parse("2026-08-22T10:00:00Z");
        user.setResidenceStreet("12 rue des Lilas");
        user.setResidenceLine2("Bat. B");
        user.setResidencePostalCode("75011");
        user.setOnboardingSeenAt(seen);

        assertThat(user.getResidenceStreet()).isEqualTo("12 rue des Lilas");
        assertThat(user.getResidenceLine2()).isEqualTo("Bat. B");
        assertThat(user.getResidencePostalCode()).isEqualTo("75011");
        assertThat(user.getOnboardingSeenAt()).isEqualTo(seen);
    }
}
