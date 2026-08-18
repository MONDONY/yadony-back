package com.yadony.api.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MatchingNegotiationConfig — chargement des propriétés")
class MatchingNegotiationConfigTest {

    @Autowired
    private MatchingNegotiationConfig config;

    @Test
    @DisplayName("les surcharges du profil test sont appliquées")
    void loadsTestOverrides() {
        assertThat(config.maxRounds()).isEqualTo(3);
        assertThat(config.inactivityHours()).isEqualTo(1);
        assertThat(config.awaitingPaymentHours()).isEqualTo(24);
        assertThat(config.expireCheckCron()).isEqualTo("-");
    }
}
