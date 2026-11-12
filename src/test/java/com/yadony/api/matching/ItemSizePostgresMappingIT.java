package com.yadony.api.matching;

import com.yadony.api.triptemplate.TripTemplateEntity;
import com.yadony.api.triptemplate.TripTemplateRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V381 sur PostgreSQL embarqué, toutes migrations Flyway et {@code ddl-auto=validate} : le démarrage
 * du contexte prouve que les champs {@code maxItemSize}, {@code maxParcelWeightKg}, {@code itemSize}
 * collent aux colonnes (types et longueurs), puis un aller-retour relit les valeurs écrites.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Taille et poids max par colis (V381, Postgres, validate)")
class ItemSizePostgresMappingIT {

    private static EmbeddedPostgres postgres;

    @Autowired AnnouncementRepository announcements;
    @Autowired TripTemplateRepository templates;
    @Autowired TripRecurrenceRepository recurrences;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, username) VALUES (?, ?, ?)",
                id, "uid-" + id, ("u" + id.toString().replace("-", "")).substring(0, 20));
        return id;
    }

    @Test
    @DisplayName("annonce : taille max (enum) et poids max (NUMERIC 8,2) font l'aller-retour")
    void announcementRoundTrip() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(user());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.CAR);
        a.setPickupAddressLabel("Gare routière Bercy");
        a.setPickupLat(new BigDecimal("48.838000"));
        a.setPickupLng(new BigDecimal("2.382000"));
        a.setDeliveryAddressLabel("Gare routière Dakar");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal("10"));
        a.setTotalKg(new BigDecimal("10"));
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setMaxItemSize(ItemSize.L);
        a.setMaxParcelWeightKg(new BigDecimal("1500.50"));
        UUID id = announcements.saveAndFlush(a).getId();

        assertThat(jdbc.queryForObject("SELECT max_item_size FROM announcements WHERE id = ?", String.class, id))
                .isEqualTo("L");
        AnnouncementEntity reloaded = announcements.findById(id).orElseThrow();
        assertThat(reloaded.getMaxItemSize()).isEqualTo(ItemSize.L);
        assertThat(reloaded.getMaxParcelWeightKg()).isEqualByComparingTo("1500.50");
    }

    @Test
    @DisplayName("modèle et série : taille max (String) et poids max (DOUBLE) font l'aller-retour")
    void templateAndRecurrenceRoundTrip() {
        UUID userId = user();

        TripTemplateEntity t = new TripTemplateEntity();
        t.setUserId(userId);
        t.setLabel("Route");
        t.setDepartureCity("Paris");
        t.setArrivalCity("Dakar");
        t.setPricePerKg(8.0);
        t.setMaxItemSize("XL");
        t.setMaxParcelWeightKg(2500.5);
        UUID templateId = templates.saveAndFlush(t).getId();
        TripTemplateEntity template = templates.findById(templateId).orElseThrow();
        assertThat(template.getMaxItemSize()).isEqualTo("XL");
        assertThat(template.getMaxParcelWeightKg()).isEqualTo(2500.5);

        TripRecurrenceEntity r = new TripRecurrenceEntity();
        r.setUserId(userId);
        r.setDepartureCity("Paris");
        r.setArrivalCity("Dakar");
        r.setAvailableKg(23.0);
        r.setPricePerKg(8.0);
        r.setPickupLabel("12 rue de la Paix");
        r.setPickupLat(48.86);
        r.setPickupLng(2.33);
        r.setDeliveryLabel("Aéroport de Dakar");
        r.setDeliveryLat(14.73);
        r.setDeliveryLng(-17.49);
        r.setWeekdays("1000100");
        r.setStartDate(LocalDate.now().plusDays(1));
        r.setMaxItemSize("S");
        r.setMaxParcelWeightKg(0.5);
        UUID recurrenceId = recurrences.saveAndFlush(r).getId();
        TripRecurrenceEntity recurrence = recurrences.findById(recurrenceId).orElseThrow();
        assertThat(recurrence.getMaxItemSize()).isEqualTo("S");
        assertThat(recurrence.getMaxParcelWeightKg()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("sans taille ni poids max : colonnes NULL, relues null")
    void unsetFieldsStayNull() {
        UUID userId = user();
        TripTemplateEntity t = new TripTemplateEntity();
        t.setUserId(userId);
        t.setLabel("Avion");
        t.setDepartureCity("Paris");
        t.setArrivalCity("Dakar");
        t.setPricePerKg(8.0);
        UUID id = templates.saveAndFlush(t).getId();
        TripTemplateEntity reloaded = templates.findById(id).orElseThrow();
        assertThat(reloaded.getMaxItemSize()).isNull();
        assertThat(reloaded.getMaxParcelWeightKg()).isNull();
    }
}
