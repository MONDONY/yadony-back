package com.yadony.api.payments.integrity;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La sonde sur Postgres embarqué avec toutes les migrations Flyway : chaque invariant est une
 * requête SQL écrite contre le schéma réel, une colonne renommée doit casser ce test et non la
 * sonde en production.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class MoneyIntegrityMonitorIT {

    private static EmbeddedPostgres postgres;

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

    @Autowired private MoneyIntegrityMonitor monitor;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoBean private AdminAlertEscalator alertEscalator;

    @AfterEach
    void cleanDb() {
        jdbcTemplate.update("DELETE FROM wallet_accounts");
        // Grand livre en ajout seul (V309) : DELETE refusé, TRUNCATE permis pour les tests.
        // CASCADE : des tables y font référence (vides dans ce test).
        jdbcTemplate.execute("TRUNCATE wallet_transactions CASCADE");
    }

    @Test
    void inv02_deuxMouvementsDeMemeHorodatage_suiventLOrdreDInsertion() {
        // Deux mouvements écrits dans la même transaction partagent created_at. L'id du
        // second est choisi plus petit que celui du premier : trié par (created_at, id),
        // l'ancien ordre lisait le second avant le premier et voyait une fausse rupture.
        UUID userId = persistUser();
        java.sql.Timestamp sameInstant = java.sql.Timestamp.from(java.time.Instant.parse("2026-10-19T10:00:00Z"));
        String insert = "INSERT INTO wallet_transactions (id, user_id, currency, type, amount, balance_after, created_at)"
                + " VALUES (?, ?, 'EUR', ?, ?, ?, ?)";
        jdbcTemplate.update(insert, UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"), userId, "TOP_UP",
                new BigDecimal("10.00"), new BigDecimal("10.00"), sameInstant);
        jdbcTemplate.update(insert, UUID.fromString("00000000-0000-4000-8000-000000000000"), userId, "TOP_UP",
                new BigDecimal("5.00"), new BigDecimal("15.00"), sameInstant);

        // Portefeuille aligné sur le grand livre : INV-01 reste à 0. La sonde garde le dernier
        // compte de chaque règle entre deux passages (bean partagé par les tests) : un INV-01 à 1
        // ici empêcherait l'alerte attendue par unSoldeSansMouvement… si elle passe après.
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("15.00"));
        walletAccountRepository.saveAndFlush(wallet);

        Map<String, Long> counts = monitor.runAll();
        assertThat(counts).containsEntry("INV-02", 0L).containsEntry("INV-01", 0L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT seq FROM wallet_transactions WHERE user_id = ? ORDER BY seq", Long.class, userId))
                .hasSize(2).isSorted();
    }

    @Test
    void lesDixSeptInvariantsTournentSurLeSchemaReelEtSontAZeroSurUneBaseVide() {
        Map<String, Long> counts = monitor.runAll();

        assertThat(counts).hasSize(MoneyInvariants.ALL.size());
        assertThat(MoneyInvariants.ALL).hasSize(17);
        assertThat(counts.values()).allSatisfy(n -> assertThat(n).isZero());
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unSoldeSansMouvementEstDetecteAlerteUneFoisEtExposeAPrometheus() {
        // Solde de 10 € sans aucune ligne au grand livre : de l'argent apparu de nulle part.
        UUID userId = persistUser();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("10.00"));
        walletAccountRepository.saveAndFlush(wallet);

        Map<String, Long> first = monitor.runAll();
        Map<String, Long> second = monitor.runAll();

        assertThat(first).containsEntry("INV-01", 1L);
        assertThat(second).containsEntry("INV-01", 1L);
        // Une seule alerte : le compte n'a pas augmenté entre les deux passages.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(alertEscalator, times(1)).raiseOnce(eq("MONEY_INVARIANT_INV-01"), eq("CRITICAL"), anyString(),
                context.capture());
        // L'alerte embarque la ligne fautive : l'admin sait quel wallet corriger.
        assertThat(context.getValue().get("exemples")).asString().contains(wallet.getId().toString());
        assertThat(meterRegistry.get("yadony.money.invariant.violations")
                .tag("invariant", "INV-01").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void inspectReExecuteLaRegleEtRendLesLignesEnFaute() {
        UUID userId = persistUser();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("10.00"));
        walletAccountRepository.saveAndFlush(wallet);

        MoneyIntegrityMonitor.Inspection inspection = monitor.inspect("INV-01", 50).orElseThrow();

        assertThat(inspection.total()).isEqualTo(1L);
        assertThat(inspection.severity()).isEqualTo("CRITIQUE");
        assertThat(inspection.rows()).hasSize(1);
        assertThat(inspection.rows().get(0)).containsEntry("wallet_id", wallet.getId().toString());
        assertThat(monitor.inspect("INV-99", 50)).isEmpty();
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("money-integrity-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }
}
