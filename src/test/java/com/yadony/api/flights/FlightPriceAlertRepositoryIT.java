package com.yadony.api.flights;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Base partagée entre tests : toutes les assertions passent par l'id de l'alerte créée ici. */
@SpringBootTest
@ActiveProfiles("test")
class FlightPriceAlertRepositoryIT {

    @Autowired FlightPriceAlertRepository alerts;

    private FlightPriceAlertEntity saved() {
        FlightPriceAlertEntity a = new FlightPriceAlertEntity();
        a.setOwnerId(UUID.randomUUID());
        a.setOriginCode("PAR");
        a.setDestinationCode("ABJ");
        a.setMaxPrice(new BigDecimal("400.00"));
        a.setCurrency("EUR");
        a.setActive(true);
        return alerts.save(a);
    }

    @Test
    void matchColumns_arePersisted() {
        FlightPriceAlertEntity a = saved();
        a.setLastMatchCount(3);
        a.setLastMatchAirlines("AF,SS,TU");
        alerts.save(a);

        FlightPriceAlertEntity read = alerts.findById(a.getId()).orElseThrow();
        assertThat(read.getLastMatchCount()).isEqualTo(3);
        assertThat(read.getLastMatchAirlines()).isEqualTo("AF,SS,TU");
    }

    @Test
    void recordFirstCheck_onlyOnce_andOnlyOnNeverCheckedAlerts() {
        FlightPriceAlertEntity a = saved();
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 0);

        assertThat(alerts.recordFirstCheck(a.getId(), new BigDecimal("327.00"), now, 2, "AF,SS")).isEqualTo(1);
        // Déjà vérifiée : la seconde écriture ne passe pas.
        assertThat(alerts.recordFirstCheck(a.getId(), new BigDecimal("1.00"), now.plusHours(1), 9, "XX"))
                .isZero();

        FlightPriceAlertEntity read = alerts.findById(a.getId()).orElseThrow();
        assertThat(read.getLastSeenPrice()).isEqualByComparingTo("327");
        assertThat(read.getLastCheckedAt()).isEqualTo(now);
        assertThat(read.getLastMatchCount()).isEqualTo(2);
        assertThat(read.getLastMatchAirlines()).isEqualTo("AF,SS");
        assertThat(read.isActive()).isTrue();
        assertThat(read.getLastNotifiedPrice()).isNull();
    }

    @Test
    void recordFirstCheck_ignoresDeletedAlerts() {
        FlightPriceAlertEntity a = saved();
        a.softDelete();
        alerts.save(a);

        assertThat(alerts.recordFirstCheck(a.getId(), BigDecimal.TEN, LocalDateTime.now(), 1, "AF")).isZero();
    }
}
