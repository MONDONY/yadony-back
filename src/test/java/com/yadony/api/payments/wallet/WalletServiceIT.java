package com.yadony.api.payments.wallet;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class WalletServiceIT {

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
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void currencyScopedOperations_persistIsolatedBalancesAndTransactionCurrencies() {
        // Catalogue réduit à EUR/XOF/XAF le 2026-08-19 (lot 1, migration V223) — XAF
        // remplace l'ancien exemple CAD, retiré du catalogue et donc désormais rejeté
        // par la contrainte CHECK chk_wallet_accounts_currency (exactement ce que ce
        // test vérifie en creusant jusqu'à la vraie base).
        UUID userId = persistUser();

        walletService.credit(userId, "EUR", new BigDecimal("20.00"),
                WalletTransactionType.TOP_UP, "pi-eur", "wallet-it-eur");
        walletService.credit(userId, "XAF", new BigDecimal("15.00"),
                WalletTransactionType.TOP_UP, "pi-xaf", "wallet-it-xaf");
        walletService.debit(userId, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.BID_PAYMENT, null);

        walletAccountRepository.flush();
        walletTransactionRepository.flush();
        entityManager.clear();

        WalletAccountEntity eurWallet = walletAccountRepository
                .findByUserIdAndCurrency(userId, "EUR")
                .orElseThrow();
        WalletAccountEntity xafWallet = walletAccountRepository
                .findByUserIdAndCurrency(userId, "XAF")
                .orElseThrow();
        List<WalletTransactionEntity> transactions = walletTransactionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10))
                .getContent();

        assertThat(eurWallet.getBalance()).isEqualByComparingTo("15.00");
        assertThat(xafWallet.getBalance()).isEqualByComparingTo("15.00");
        assertThat(transactions)
                .extracting(WalletTransactionEntity::getCurrency)
                .containsExactlyInAnyOrder("EUR", "XAF", "EUR");
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-service-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }
}
