package com.yadony.api.payments.wallet;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V330 sur Postgres embarqué, toutes migrations Flyway et ddl-auto=validate : les colonnes
 * {@code paid_amount} et {@code resolution_note} collent à l'entité, et un montant versé nul ou
 * négatif est refusé par la base.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
@DisplayName("Remboursement de solde - montant versé (V330, Postgres)")
class WalletRefundPaidAmountPostgresIT {

    private static EmbeddedPostgres postgres;

    @Autowired UserRepository userRepository;
    @Autowired WalletRefundRequestRepository refundRequestRepository;
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

    @Test
    @DisplayName("le montant versé et la note sont relus tels quels ; nuls par défaut")
    void paidAmountAndNoteRoundTrip() {
        UserEntity user = persistUser();
        WalletRefundRequestEntity resolved = request(user.getId(), WalletRefundRequestStatus.RESOLVED);
        resolved.setPaidAmount(new BigDecimal("487"));
        resolved.setResolutionNote("Versé par Wave : le wallet pawaPay n'avait encaissé que 487 XOF");
        resolved = refundRequestRepository.saveAndFlush(resolved);
        WalletRefundRequestEntity pending = refundRequestRepository.saveAndFlush(
                request(user.getId(), WalletRefundRequestStatus.PENDING));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_amount FROM wallet_refund_requests WHERE id = ?", BigDecimal.class, resolved.getId()))
                .isEqualByComparingTo("487");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT resolution_note FROM wallet_refund_requests WHERE id = ?", String.class, resolved.getId()))
                .isEqualTo("Versé par Wave : le wallet pawaPay n'avait encaissé que 487 XOF");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_amount FROM wallet_refund_requests WHERE id = ?", BigDecimal.class, pending.getId()))
                .isNull();
    }

    @Test
    @DisplayName("un montant versé nul ou négatif est refusé par la contrainte CHECK")
    void nonPositivePaidAmountIsRejected() {
        UserEntity user = persistUser();
        WalletRefundRequestEntity saved = refundRequestRepository.saveAndFlush(
                request(user.getId(), WalletRefundRequestStatus.RESOLVED));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE wallet_refund_requests SET paid_amount = 0 WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static WalletRefundRequestEntity request(UUID userId, WalletRefundRequestStatus status) {
        WalletRefundRequestEntity r = new WalletRefundRequestEntity();
        r.setUserId(userId);
        r.setCurrency(status == WalletRefundRequestStatus.PENDING ? "EUR" : "XOF");
        r.setAmount(status == WalletRefundRequestStatus.PENDING ? new BigDecimal("12.50") : new BigDecimal("500"));
        r.setStatus(status);
        r.setChannel(WalletRefundChannel.MANUAL_ADMIN);
        r.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        return r;
    }

    private UserEntity persistUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-refund-paid-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new HashSet<>(Set.of(Role.SENDER)));
        u.setTotalTrips(0);
        return userRepository.saveAndFlush(u);
    }
}
