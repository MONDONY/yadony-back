package com.yadony.api.travelerstatus;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Valide V373 sur Postgres embarqué : toute la chaîne Flyway, ddl-auto=validate, index et requêtes de détection. */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
@DisplayName("Alerte particuliers - schéma V373")
class IndividualFrequencySchemaPostgresIT {

    private static EmbeddedPostgres postgres;

    @Autowired UserRepository userRepository;
    @Autowired IndividualFrequencyFlagRepository flagRepository;
    @Autowired IndividualFrequencyEvaluator evaluator;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
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

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user.setFirebaseUid("uid-freq-pg-" + suffix);
        user.setUsername("freq-pg-" + suffix);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(new HashSet<>(Set.of(Role.TRAVELER)));
        user.setTravelerStatus(TravelerStatus.INDIVIDUAL);
        return userRepository.saveAndFlush(user);
    }

    private IndividualFrequencyFlagEntity flag(UUID userId) {
        IndividualFrequencyFlagEntity f = new IndividualFrequencyFlagEntity();
        f.setUserId(userId);
        f.setTripsCount(5);
        f.setWindowDays(30);
        f.setThreshold(4);
        f.setDetectedAt(LocalDateTime.now(ZoneOffset.UTC));
        return f;
    }

    @Test
    @DisplayName("la migration s'applique, l'entité valide et les requêtes de détection tournent sur Postgres")
    void migrationAppliesAndQueriesRun() {
        assertThat(flagRepository.findByResolvedAtIsNull()).isEmpty();
        assertThat(evaluator.detect(LocalDateTime.now(ZoneOffset.UTC), 4)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT setting_key FROM platform_settings WHERE setting_key = 'individual_trips_alert_threshold'",
                String.class)).isEqualTo("individual_trips_alert_threshold");
    }

    @Test
    @DisplayName("un seul drapeau non résolu par utilisateur ; un drapeau résolu n'empêche pas d'en créer un autre")
    void oneUnresolvedFlagPerUser() {
        UUID userId = persistUser().getId();
        flagRepository.saveAndFlush(flag(userId));

        assertThatThrownBy(() -> flagRepository.saveAndFlush(flag(userId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("après résolution, un nouveau drapeau peut apparaître")
    void resolvedFlagAllowsNewOne() {
        UUID userId = persistUser().getId();
        IndividualFrequencyFlagEntity first = flagRepository.saveAndFlush(flag(userId));
        first.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        flagRepository.saveAndFlush(first);

        flagRepository.saveAndFlush(flag(userId));

        assertThat(flagRepository.findByResolvedAtIsNull()).hasSize(1);
    }

    @Autowired com.yadony.api.matching.AnnouncementRepository announcementRepository;
    @Autowired com.yadony.api.matching.BidRepository bidRepository;
    @Autowired com.yadony.api.gppro.GpProApplicationRepository applicationRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private UUID trip(UUID travelerId, java.time.LocalDate departureDate) {
        com.yadony.api.matching.AnnouncementEntity a = new com.yadony.api.matching.AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(departureDate);
        a.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        a.setPickupAddressLabel("Paris");
        a.setPickupLat(new java.math.BigDecimal("48.860000"));
        a.setPickupLng(new java.math.BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar");
        a.setDeliveryLat(new java.math.BigDecimal("14.693000"));
        a.setDeliveryLng(new java.math.BigDecimal("-17.447000"));
        a.setAvailableKg(new java.math.BigDecimal("10.00"));
        a.setTotalKg(new java.math.BigDecimal("10.00"));
        a.setPricePerKg(new java.math.BigDecimal("5.00"));
        a.setStatus(com.yadony.api.matching.AnnouncementStatus.ACTIVE);
        return announcementRepository.saveAndFlush(a).getId();
    }

    private void acceptedBid(UUID tripId, UUID senderId) {
        com.yadony.api.matching.BidEntity b = new com.yadony.api.matching.BidEntity();
        b.setAnnouncementId(tripId);
        b.setSenderId(senderId);
        b.setStatus(com.yadony.api.matching.BidStatus.ACCEPTED);
        b.setWeightKg(new java.math.BigDecimal("2.00"));
        bidRepository.saveAndFlush(b);
    }

    @Test
    @DisplayName("détection sur Postgres avec de vraies données : voyages, même jour, demande hebdomadaire refusée")
    void detectionWithRealData() {
        UUID sender = persistUser().getId();
        UUID frequent = persistUser().getId();
        UUID weekly = persistUser().getId();
        java.time.LocalDate base = java.time.LocalDate.now().plusDays(10);
        for (int i = 0; i < 5; i++) {
            acceptedBid(trip(frequent, base.plusDays(i)), sender);
        }
        // Deux trajets dédiés du même vol : un seul voyage de plus.
        acceptedBid(trip(frequent, base), sender);
        acceptedBid(trip(weekly, base), sender);
        acceptedBid(trip(weekly, base.plusDays(3)), sender);
        com.yadony.api.gppro.GpProApplicationEntity app = new com.yadony.api.gppro.GpProApplicationEntity();
        app.setUserId(weekly);
        app.setStatus(com.yadony.api.gppro.GpProApplicationStatus.REJECTED);
        app.setSource(com.yadony.api.gppro.GpProApplicationSource.PROFILE);
        app.setTravelFrequency(com.yadony.api.gppro.GpProTravelFrequency.WEEKLY);
        app.setDecidedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2));
        applicationRepository.saveAndFlush(app);
        entityManager.clear();

        var result = evaluator.detect(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1), 4);

        assertThat(result.get(frequent)).isEqualTo(new IndividualFrequencyEvaluator.Detection(5, false));
        // countTrips (liste IN) exécutée sur Postgres pour le déclencheur hebdomadaire sous le seuil.
        assertThat(result.get(weekly)).isEqualTo(new IndividualFrequencyEvaluator.Detection(2, true));
        assertThat(result).doesNotContainKey(sender);
    }

    @Test
    @DisplayName("écarter et résoudre par écriture conditionnelle")
    void conditionalWrites() {
        UUID userId = persistUser().getId();
        IndividualFrequencyFlagEntity f = flagRepository.saveAndFlush(flag(userId));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(flagRepository.dismissIfOpen(f.getId(), UUID.randomUUID(), now)).isEqualTo(1);
        assertThat(flagRepository.dismissIfOpen(f.getId(), UUID.randomUUID(), now)).isZero();
        assertThat(flagRepository.resolveIfOpen(f.getId(), now)).isEqualTo(1);
        assertThat(flagRepository.resolveIfOpen(f.getId(), now)).isZero();
        IndividualFrequencyFlagEntity reloaded = flagRepository.findById(f.getId()).orElseThrow();
        assertThat(reloaded.getDismissedAt()).isNotNull();
        assertThat(reloaded.getResolvedAt()).isNotNull();
        assertThat(flagRepository.existsByUserIdAndDetectedAtGreaterThanEqual(userId, now.minusDays(90))).isTrue();
    }

    @Test
    @DisplayName("seuil hors bornes refusé par la base")
    void thresholdOutOfBoundsRejected() {
        UUID userId = persistUser().getId();
        IndividualFrequencyFlagEntity f = flag(userId);
        f.setThreshold(0);

        assertThatThrownBy(() -> flagRepository.saveAndFlush(f)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
