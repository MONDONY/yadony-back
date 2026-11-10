package com.yadony.api.kyc.reminder;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycReminderPropertiesTest {

    private static KycReminderProperties of(Duration first, int start, int end) {
        return new KycReminderProperties(true, first, Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(10), start, end, List.of());
    }

    @Test
    void valeursValides_acceptees() {
        assertThat(of(Duration.ofDays(1), 10, 20).windowEndHour()).isEqualTo(20);
    }

    @Test
    void fenetreInversee_refusee() {
        assertThatThrownBy(() -> of(Duration.ofDays(1), 20, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(Duration.ofDays(1), 10, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delaiNulOuNegatif_refuse() {
        assertThatThrownBy(() -> of(Duration.ZERO, 10, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(Duration.ofDays(-1), 10, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delaisNonCroissants_refuses() {
        assertThatThrownBy(() -> new KycReminderProperties(true, Duration.ofDays(3), Duration.ofDays(3),
                Duration.ofDays(7), Duration.ofDays(10), 10, 20, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KycReminderProperties(true, Duration.ofDays(1), Duration.ofDays(7),
                Duration.ofDays(3), Duration.ofDays(10), 10, 20, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ageMaxDeLAncre_doitDepasserLeTroisiemeDelai() {
        assertThatThrownBy(() -> new KycReminderProperties(true, Duration.ofDays(1), Duration.ofDays(3),
                Duration.ofDays(7), Duration.ofDays(7), 10, 20, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new KycReminderProperties(true, Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7),
                Duration.ofDays(8), 10, 20, List.of()).maxAnchorAge()).isEqualTo(Duration.ofDays(8));
    }
}
