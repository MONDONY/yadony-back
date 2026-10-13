package com.yadony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReliabilityScore — taux de fiabilité du profil public (FLUTTER-NV)")
class ReliabilityScoreTest {

    @ParameterizedTest(name = "{0} livrés, {1} échecs → {2} %")
    @CsvSource({
            "3, 0, 100",
            "2, 1, 67",     // 66,67 → 67
            "1, 2, 33",
            "0, 3, 0",
            "1, 1, ",       // 2 engagés : sous le seuil
            "0, 0, ",
            "7, 1, 88",     // 87,5 → 88 (demi vers le haut)
            "199, 1, 99",   // 99,5 arrondirait à 100 : plafonné à 99 avec un échec
            "1000, 1, 99",
            "5, 0, 100",
    })
    void percent(long completed, long failures, Integer expected) {
        assertThat(ReliabilityScore.percent(completed, failures)).isEqualTo(expected);
    }

    @Test
    void seuilDeTroisEnvoisEngages() {
        assertThat(ReliabilityScore.MIN_ENGAGED_SHIPMENTS).isEqualTo(3);
        assertThat(ReliabilityScore.percent(2, 0)).isNull();
        assertThat(ReliabilityScore.percent(3, 0)).isEqualTo(100);
    }

    @Test
    void valeursNegativesIgnorees() {
        assertThat(ReliabilityScore.percent(-4, 3)).isZero();
    }

    @Test
    void echecsImputables_sommeDesQuatreCompteursDeReputation() {
        UserEntity user = new UserEntity();
        user.setCancellationCount(1);
        user.setNoShowCount(2);
        user.setSenderCancellationCount(3);
        user.setSenderHandoverIncidentCount(4);
        assertThat(ReliabilityScore.attributableFailures(user)).isEqualTo(10);
        assertThat(ReliabilityScore.attributableFailures(new UserEntity())).isZero();
    }
}
