package com.yadony.api.payments.wallet;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentCommand;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentResult;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction de solde admin sur un vrai Postgres migré par Flyway (V266 comprise, schéma
 * validé par Hibernate) : la contrainte {@code wallet_transactions_type_check} accepte les
 * deux nouveaux types, les colonnes admin sont persistées, et le rejeu du ledger
 * ({@link WalletRefundAllocator}) retombe toujours sur le solde.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class WalletAdminAdjustmentIT {

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

    @Autowired private WalletAdminAdjustmentService adjustmentService;
    @Autowired private WalletService walletService;
    @Autowired private WalletSelfRefundService selfRefundService;
    @Autowired private WalletTransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private static final String REASON = "Geste commercial suite au litige";

    @Test
    void creditPuisDebitAdmin_ledgerCoherentEtCreditNonRemboursable() {
        UUID userId = persistUser();
        UUID adminId = UUID.randomUUID();
        walletService.credit(userId, "EUR", new BigDecimal("100.00"), WalletTransactionType.TOP_UP,
                "pi_admin_adjust_it", "topup-admin-adjust-it-" + userId);

        AdjustmentResult credit = adjustmentService.adjust(new AdjustmentCommand(userId, adminId, "EUR", "CREDIT",
                new BigDecimal("20"), REASON, "it-credit-" + userId));
        entityManager.flush();
        entityManager.clear();

        WalletTransactionEntity stored = transactionRepository.findById(credit.transaction().getId()).orElseThrow();
        assertThat(stored.getType()).isEqualTo(WalletTransactionType.ADMIN_CREDIT);
        assertThat(stored.getAdminReason()).isEqualTo(REASON);
        assertThat(stored.getAdminActorId()).isEqualTo(adminId);

        WalletRefundAllocation afterCredit = selfRefundService.allocation(userId, "EUR");
        assertThat(afterCredit.refundableTotal()).isEqualByComparingTo("100.00");
        assertThat(afterCredit.nonRefundable()).isEqualByComparingTo("20.00");

        adjustmentService.adjust(new AdjustmentCommand(userId, adminId, "EUR", "DEBIT",
                new BigDecimal("30"), REASON, "it-debit-" + userId));
        entityManager.flush();
        entityManager.clear();

        WalletRefundAllocation afterDebit = selfRefundService.allocation(userId, "EUR");
        assertThat(afterDebit.nonRefundable()).isEqualByComparingTo("0");
        assertThat(afterDebit.refundableTotal()).isEqualByComparingTo("90.00");

        List<WalletAccountView> accounts = adjustmentService.accounts(userId);
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("90.00");
        assertThat(accounts.get(0).refundEligibleAmount()).isEqualByComparingTo("90.00");
        assertThat(accounts.get(0).frozen()).isFalse();
    }

    @Test
    void rejeuMemeCle_uneSeuleEcriture() {
        UUID userId = persistUser();
        UUID adminId = UUID.randomUUID();
        walletService.credit(userId, "XOF", new BigDecimal("5000"), WalletTransactionType.TOP_UP,
                "pawapay:" + UUID.randomUUID(), "topup-admin-adjust-it-xof-" + userId);
        AdjustmentCommand command = new AdjustmentCommand(userId, adminId, "XOF", "DEBIT",
                new BigDecimal("1500"), REASON, "it-replay-" + userId);

        AdjustmentResult first = adjustmentService.adjust(command);
        entityManager.flush();
        AdjustmentResult second = adjustmentService.adjust(command);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(walletService.getBalance(userId, "XOF")).isEqualByComparingTo("3500");
        assertThat(transactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, "XOF")).hasSize(2);
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-admin-adjust-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }
}
