package com.yadony.api.payments.wallet;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Course entre un débit de commission et une demande de remboursement du même portefeuille,
 * sur une vraie base. {@code request()} calcule son allocation sur le grand livre, PUIS écrit la
 * demande ; sans verrou du portefeuille entre les deux, un débit qui commite dans l'intervalle
 * laisse une demande plus grande que le solde réel. L'argent partirait chez Stripe, puis
 * {@code debitConfirmedRefund} lèverait {@code InsufficientWalletBalanceException} et la demande
 * resterait PROCESSING.
 *
 * <p>Déroulé déterministe, sans {@code sleep} : on ouvre l'intervalle en bloquant l'INSERT de la
 * demande. Une transaction concurrente détient une demande PENDING non commitée sur le même
 * (utilisateur, devise) ; l'index unique partiel {@code uq_wallet_refund_requests_pending} fait
 * attendre l'INSERT de la vraie demande, APRÈS son calcul d'allocation. Un débit part alors :
 * soit il commite (ancien comportement, la demande garde un montant périmé), soit il se met en
 * file derrière le verrou du portefeuille que la demande a pris dès le début. On libère ensuite.
 * L'invariant à tenir dans tous les ordres : la demande ne dépasse jamais le solde réel.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class WalletRefundDebitRaceIT {

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

    // Jamais de réseau en IT : frais Stripe nuls, client pawaPay simulé, et l'émission du
    // remboursement (qui part au commit de la demande) neutralisée.
    @MockitoBean StripeFeeSource stripeFeeSource;
    @MockitoBean PawapayClient pawapayClient;
    @MockitoBean WalletRefundIssueListener issueListener;

    @Autowired private WalletService walletService;
    @Autowired private WalletSelfRefundService walletSelfRefundService;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private WalletRefundRequestRepository refundRequestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void stubStripeFees() {
        when(stripeFeeSource.fee(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void debitPendantLeCalculDeLaDemande_laDemandeNeDepasseJamaisLeSoldeReel() throws Exception {
        UUID userId = persistUser();
        walletService.credit(userId, "EUR", new BigDecimal("100.00"), WalletTransactionType.TOP_UP,
                "pi_race_" + userId, "topup-" + userId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch blockerInserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Throwable debitFailure;
        try {
            // Demande PENDING non commitée : elle occupe l'index unique et fait attendre l'INSERT
            // de la vraie demande. Annulée à la fin, comme si elle n'avait jamais existé.
            Future<?> blocker = pool.submit(() -> tx.executeWithoutResult(status -> {
                WalletRefundRequestEntity pending = new WalletRefundRequestEntity();
                pending.setUserId(userId);
                pending.setCurrency("EUR");
                pending.setAmount(BigDecimal.ONE);
                pending.setChannel(WalletRefundChannel.MANUAL_ADMIN);
                pending.setStatus(WalletRefundRequestStatus.PENDING);
                pending.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
                refundRequestRepository.saveAndFlush(pending);
                blockerInserted.countDown();
                await(release);
                status.setRollbackOnly();
            }));
            assertThat(blockerInserted.await(30, TimeUnit.SECONDS)).isTrue();

            Future<?> refundRequest = pool.submit(() ->
                    walletSelfRefundService.request(userId, "EUR", List.of()));
            // La demande a calculé son allocation (solde 100) et attend maintenant son INSERT.
            awaitSessionsWaitingOnLock(1);

            Future<Throwable> debit = pool.submit(() -> {
                try {
                    walletService.debit(userId, "EUR", new BigDecimal("30.00"),
                            WalletTransactionType.COMMISSION_DEDUCTED, (UUID) null);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            // Soit le débit passe et commite (la demande n'a pas verrouillé le portefeuille),
            // soit il attend derrière le verrou que la demande tient déjà.
            awaitWaitingOrDone(2, debit);

            release.countDown();
            blocker.get(30, TimeUnit.SECONDS);
            refundRequest.get(30, TimeUnit.SECONDS);
            debitFailure = debit.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // Le débit réussit (avant la demande) ou est refusé net par le gel : rien d'autre.
        if (debitFailure != null) {
            assertThat(debitFailure).hasFieldOrPropertyWithValue("errorCode", "wallet-refund-pending");
        }
        Map<String, Object> request = jdbc.queryForMap(
                "SELECT amount, status FROM wallet_refund_requests WHERE user_id = ?", userId);
        BigDecimal requested = (BigDecimal) request.get("amount");
        BigDecimal balance = walletAccountRepository.findByUserIdAndCurrency(userId, "EUR").orElseThrow().getBalance();

        assertThat(requested)
                .as("la demande (%s) ne doit jamais dépasser le solde réel (%s) : sinon le remboursement "
                        + "part chez Stripe sans que le grand livre puisse le débiter", requested, balance)
                .isLessThanOrEqualTo(balance);
        BigDecimal ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions WHERE user_id = ? AND currency = 'EUR'",
                BigDecimal.class, userId);
        assertThat(balance).isEqualByComparingTo(ledgerSum);
    }

    private void awaitSessionsWaitingOnLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (sessionsWaitingOnLock() >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(expected + " session(s) auraient dû attendre le verrou du portefeuille");
    }

    private void awaitWaitingOrDone(int expectedWaiting, Future<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (future.isDone() || sessionsWaitingOnLock() >= expectedWaiting) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("La demande n'est ni bloquée sur le verrou ni terminée");
    }

    private int sessionsWaitingOnLock() {
        Integer waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                        + "AND datname = current_database()", Integer.class);
        return waiting == null ? 0 : waiting;
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

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-race-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }
}
