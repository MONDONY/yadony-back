package com.yadony.api.payments.wallet;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot 3 (2026-08-19/20) : le parrainage ne crédite plus le wallet — il octroie un bon
 * de réduction de commission (voir {@code com.yadony.api.voucher}). REFERRAL_REWARD
 * reste une valeur valide de {@link WalletTransactionType} et de la contrainte CHECK
 * V121 (lignes historiques déjà écrites), mais plus rien ne l'émet. Ce test garde
 * uniquement la preuve que la contrainte CHECK accepte toujours ce type — le reste
 * (listener, event) a été retiré avec son test dédié.
 *
 * <p>Sur Postgres embarqué avec les migrations Flyway : c'est la vraie contrainte V121
 * qui est éprouvée (le schéma H2 généré n'en a pas), et le crédit verrouille la ligne
 * du wallet ({@code FOR NO KEY UPDATE}, syntaxe que H2 refuse).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class ReferralRewardWalletIT {

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

    @Autowired private WalletService walletService;
    @Autowired private UserRepository userRepository;

    @Test
    void credit_withReferralRewardType_passesDbCheckConstraint() {
        UUID userId = persistUser();

        // Would throw a constraint-violation if V121 hadn't extended wallet_transactions_type_check
        walletService.credit(userId, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.REFERRAL_REWARD, "ref-1", "referral-reward-it-1");

        assertThat(walletService.getBalance(userId, "EUR")).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("referral-reward-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }
}
