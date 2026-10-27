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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotence du grand livre sous concurrence, sur une vraie base (Postgres embarqué, chaque
 * thread commite pour de vrai : pas de {@code @Transactional} sur la classe).
 *
 * <p>Scénario déterministe, sans {@code sleep} : un thread tient le verrou de la ligne du
 * portefeuille, deux autres appellent la MÊME opération avec la MÊME clé et se bloquent sur ce
 * verrou, puis on le libère. Le premier écrit sa ligne, le second doit voir la clé et sortir
 * sans effet, sans exception. Avant le correctif (clé testée avant le verrou), le second passait
 * le test de clé puis butait sur {@code UNIQUE(idempotency_key)} à l'écriture.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class WalletIdempotencyConcurrencyIT {

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

    private static final BigDecimal TEN = new BigDecimal("10.00");

    @Autowired private WalletService walletService;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void creditMemeCle_deuxAppelsSimultanes_unSeulCredit_aucuneException() throws Exception {
        UUID userId = persistUserWithBalance("100.00");
        String key = "idem-credit-" + UUID.randomUUID();

        runTwoConcurrentCalls(userId, () -> walletService.credit(userId, "EUR", TEN,
                WalletTransactionType.TOP_UP, "pi-" + key, key));

        assertThat(balance(userId)).isEqualByComparingTo("110.00");
        assertThat(rowsWithKey(key)).isEqualTo(1);
    }

    @Test
    void debitAvecReferenceEtCle_deuxAppelsSimultanes_unSeulDebit_aucuneException() throws Exception {
        UUID userId = persistUserWithBalance("100.00");
        String key = "idem-debit-ref-" + UUID.randomUUID();

        runTwoConcurrentCalls(userId, () -> walletService.debit(userId, "EUR", TEN,
                WalletTransactionType.COMMISSION_DEDUCTED, "ref-" + key, key));

        assertThat(balance(userId)).isEqualByComparingTo("90.00");
        assertThat(rowsWithKey(key)).isEqualTo(1);
    }

    @Test
    void debitAvecConversionEtCle_deuxAppelsSimultanes_unSeulDebit_aucuneException() throws Exception {
        UUID userId = persistUserWithBalance("100.00");
        String key = "idem-debit-conv-" + UUID.randomUUID();

        runTwoConcurrentCalls(userId, () -> walletService.debit(userId, "EUR", TEN,
                WalletTransactionType.COMMISSION_DEDUCTED, "ref-" + key, key,
                "XOF", new BigDecimal("6560"), new BigDecimal("656.000000")));

        assertThat(balance(userId)).isEqualByComparingTo("90.00");
        assertThat(rowsWithKey(key)).isEqualTo(1);
    }

    @Test
    void debitRattacheAUnColisAvecCle_deuxAppelsSimultanes_unSeulDebit_aucuneException() throws Exception {
        UUID userId = persistUserWithBalance("100.00");
        String key = "idem-debit-bid-" + UUID.randomUUID();

        runTwoConcurrentCalls(userId, () -> walletService.debit(userId, "EUR", TEN,
                WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED, (UUID) null, "ref-" + key, key,
                null, null, null));

        assertThat(balance(userId)).isEqualByComparingTo("90.00");
        assertThat(rowsWithKey(key)).isEqualTo(1);
    }

    /**
     * Mise à jour perdue : le portefeuille est déjà lu dans la transaction de l'appelant (cache de
     * premier niveau, solde 100), un autre crédit de 5 est commité entre-temps, puis l'appelant
     * crédite 10. Le verrou de ligne ne suffit pas à lui seul : Hibernate garde l'état périmé de
     * l'entité déjà chargée, et l'UPDATE écraserait le crédit concurrent (110 au lieu de 115).
     */
    @Test
    void creditApresLectureDansLaMemeTransaction_neEcrasePasUnCreditCommiteEntreTemps() {
        UUID userId = persistUserWithBalance("100.00");

        inCallerTransaction(() -> {
            walletAccountRepository.findByUserIdAndCurrency(userId, "EUR").orElseThrow();
            inIndependentTransaction(() -> walletService.credit(userId, "EUR", new BigDecimal("5.00"),
                    WalletTransactionType.TOP_UP, "pi-in-" + userId, "k-in-" + userId));
            walletService.credit(userId, "EUR", TEN, WalletTransactionType.TOP_UP,
                    "pi-out-" + userId, "k-out-" + userId);
        });

        assertThat(balance(userId)).isEqualByComparingTo("115.00");
        assertLedgerMatchesBalance(userId);
    }

    @Test
    void debitApresLectureDansLaMemeTransaction_neEcrasePasUnDebitCommiteEntreTemps() {
        UUID userId = persistUserWithBalance("100.00");

        inCallerTransaction(() -> {
            walletAccountRepository.findByUserIdAndCurrency(userId, "EUR").orElseThrow();
            inIndependentTransaction(() -> walletService.debit(userId, "EUR", new BigDecimal("5.00"),
                    WalletTransactionType.COMMISSION_DEDUCTED, (UUID) null));
            walletService.debit(userId, "EUR", TEN, WalletTransactionType.COMMISSION_DEDUCTED, (UUID) null);
        });

        assertThat(balance(userId)).isEqualByComparingTo("85.00");
        assertLedgerMatchesBalance(userId);
    }

    private void inCallerTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private void inIndependentTransaction(Runnable work) {
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status -> work.run());
    }

    /** Invariant INV-01 du moniteur d'intégrité : le solde égale la somme des mouvements. */
    private void assertLedgerMatchesBalance(UUID userId) {
        BigDecimal ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions WHERE user_id = ? AND currency = 'EUR'",
                BigDecimal.class, userId);
        assertThat(balance(userId)).isEqualByComparingTo(ledgerSum);
    }

    /**
     * Tient le verrou du portefeuille, lance deux appels identiques qui s'y bloquent, attend que
     * les deux soient réellement en attente du verrou côté Postgres, puis le libère. Les deux
     * appels doivent se terminer sans exception.
     */
    private void runTwoConcurrentCalls(UUID userId, Runnable operation) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, "EUR").orElseThrow();
                lockHeld.countDown();
                await(release);
            }));
            assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();

            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> operation.run()));
            Future<?> second = pool.submit(() -> tx.executeWithoutResult(status -> operation.run()));
            awaitSessionsWaitingOnLock(2);

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitSessionsWaitingOnLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND datname = current_database()", Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Les " + expected + " appels ne se sont pas bloqués sur le verrou du portefeuille");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("verrou jamais libéré par le test");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private UUID persistUserWithBalance(String amount) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-idem-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        UUID userId = userRepository.saveAndFlush(user).getId();
        // Crée la ligne du portefeuille et son solde de départ, commités avant le scénario.
        walletService.credit(userId, "EUR", new BigDecimal(amount),
                WalletTransactionType.TOP_UP, "pi-seed-" + userId, "seed-" + userId);
        return userId;
    }

    private BigDecimal balance(UUID userId) {
        List<WalletAccountEntity> wallets = walletAccountRepository.findAllByUserId(userId);
        assertThat(wallets).hasSize(1);
        return wallets.get(0).getBalance();
    }

    private int rowsWithKey(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM wallet_transactions WHERE idempotency_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }
}
