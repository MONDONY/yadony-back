package com.yadony.api.payments.wallet.topupcode;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.topupcode.dto.TopupCodeRedeemResponse;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Utilisation réelle d'un code de recharge contre une vraie base PostgreSQL (migrations Flyway
 * jouées, dont V322) : verrou pessimiste sur le code, UNIQUE (code_id, user_id), CHECK du type
 * TOP_UP_CODE, ledger append-only. Aucune transaction de test englobante : chaque appel de
 * service commit pour de vrai. Le ledger étant append-only (trigger), les tests ne nettoient
 * rien et s'isolent par des utilisateurs et des codes distincts.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class WalletTopupCodeRedemptionPostgresIT {

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

    @Autowired private WalletTopupCodeRedemptionService redemptionService;
    @Autowired private WalletTopupCodeAdminService adminService;
    @Autowired private WalletTopupCodeRepository codeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TopupCodeHasher hasher;
    @Autowired private TopupCodeHashBackfill backfill;

    @Test
    void redeem_succes_crediteLeSoldeEtEcritUnTopUpCodeSansPaymentRef() {
        UUID user = persistUser().getId();
        WalletTopupCodeEntity code = createCode(10);

        TopupCodeRedeemResponse response = redemptionService.redeem(user, code.getIssuedCode().toLowerCase(Locale.ROOT));

        assertThat(response.amount()).isEqualByComparingTo("15");
        assertThat(balance(user)).isEqualByComparingTo("15");
        assertThat(jdbc.queryForList(
                "SELECT type, payment_ref, amount FROM wallet_transactions WHERE user_id = ?", user))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("type")).isEqualTo("TOP_UP_CODE");
                    assertThat(row.get("payment_ref")).isNull();
                    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("15");
                });
        assertThat(redeemedCount(code.getId())).isEqualTo(1);
        assertThat(redemptionRows(code.getId())).isEqualTo(1);
    }

    @Test
    void redeem_memeCompteDeuxFois_422_etUnSeulCredit() {
        UUID user = persistUser().getId();
        WalletTopupCodeEntity code = createCode(10);
        redemptionService.redeem(user, code.getIssuedCode());

        assertThatThrownBy(() -> redemptionService.redeem(user, code.getIssuedCode()))
                .satisfies(t -> assertProblem(t, "topup-code-already-redeemed"));

        assertThat(balance(user)).isEqualByComparingTo("15");
        assertThat(ledgerRows(user)).isEqualTo(1);
        assertThat(redeemedCount(code.getId())).isEqualTo(1);
        assertThat(redemptionRows(code.getId())).isEqualTo(1);
    }

    @Test
    void redeem_autreCompte_reussitSurLeMemeCode() {
        UUID first = persistUser().getId();
        UUID second = persistUser().getId();
        WalletTopupCodeEntity code = createCode(10);

        redemptionService.redeem(first, code.getIssuedCode());
        redemptionService.redeem(second, code.getIssuedCode());

        assertThat(balance(first)).isEqualByComparingTo("15");
        assertThat(balance(second)).isEqualByComparingTo("15");
        assertThat(redeemedCount(code.getId())).isEqualTo(2);
        assertThat(redemptionRows(code.getId())).isEqualTo(2);
    }

    @Test
    void redeem_derniereUnite_deuxUtilisateursSimultanes_unSeulSucces() throws Exception {
        UUID first = persistUser().getId();
        UUID second = persistUser().getId();
        WalletTopupCodeEntity code = createCode(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (UUID user : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        redemptionService.redeem(user, code.getIssuedCode());
                        return "OK";
                    } catch (YadonyBusinessException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "topup-code-exhausted");
        } finally {
            pool.shutdownNow();
        }

        assertThat(redeemedCount(code.getId())).isEqualTo(1);
        assertThat(redemptionRows(code.getId())).isEqualTo(1);
        assertThat(ledgerRows(first) + ledgerRows(second)).isEqualTo(1);
    }

    @Test
    void redeem_apresRevocation_422() {
        UUID admin = persistUser().getId();
        UUID user = persistUser().getId();
        WalletTopupCodeEntity code = createCode(10, admin);

        adminService.revoke(code.getId(), admin);

        assertThatThrownBy(() -> redemptionService.redeem(user, code.getIssuedCode()))
                .satisfies(t -> assertProblem(t, "topup-code-revoked"));
        assertThat(ledgerRows(user)).isZero();
        assertThat(redeemedCount(code.getId())).isZero();
    }

    @Test
    void redemptions_admin_listeLUtilisationApresSucces() {
        UUID user = persistUser().getId();
        WalletTopupCodeEntity code = createCode(10);
        TopupCodeRedeemResponse response = redemptionService.redeem(user, code.getIssuedCode());

        List<WalletTopupCodeRedemptionEntity> list = adminService.redemptions(code.getId());

        assertThat(list).singleElement().satisfies(r -> {
            assertThat(r.getUserId()).isEqualTo(user);
            assertThat(r.getCodeId()).isEqualTo(code.getId());
            assertThat(r.getAmount()).isEqualByComparingTo("15");
            assertThat(r.getCurrency()).isEqualTo("EUR");
            assertThat(r.getWalletTransactionId()).isEqualTo(response.transactionId());
        });
    }

    @Test
    void creation_neStockeJamaisLeCodeEnClair() {
        WalletTopupCodeEntity code = createCode(10);

        var row = jdbc.queryForMap("SELECT code, code_hash, code_hint FROM wallet_topup_codes WHERE id = ?",
                code.getId());
        assertThat(row.get("code")).isNull();
        assertThat((String) row.get("code_hash")).matches("[0-9a-f]{64}");
        assertThat(row.get("code_hint")).isEqualTo(TopupCodeHasher.hint(code.getIssuedCode()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND payload::text LIKE ?",
                Integer.class, code.getId(), "%" + code.getIssuedCode() + "%")).isZero();
    }

    @Test
    void codeAnterieurEnClair_hacheParLeRattrapage_resteUtilisable() {
        UUID admin = persistUser().getId();
        UUID user = persistUser().getId();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO wallet_topup_codes (id, code, amount, currency, max_redemptions, redeemed_count, "
                        + "expires_at, status, created_by, created_at, updated_at) "
                        + "VALUES (?, 'YD-LEGA-CY01', 15, 'EUR', 5, 0, ?, 'ACTIVE', ?, now(), now())",
                id, LocalDateTime.now(ZoneOffset.UTC).plusDays(5), admin);

        assertThat(backfill.backfill()).isEqualTo(1);
        assertThat(backfill.backfill()).isZero();

        var row = jdbc.queryForMap("SELECT code, code_hash, code_hint FROM wallet_topup_codes WHERE id = ?", id);
        assertThat(row.get("code")).isNull();
        assertThat(row.get("code_hash")).isEqualTo(hasher.hash("YD-LEGA-CY01"));
        assertThat(row.get("code_hint")).isEqualTo("01");
        TopupCodeRedeemResponse response = redemptionService.redeem(user, "yd-lega-cy01");
        assertThat(response.amount()).isEqualByComparingTo("15");
    }

    @Test
    void base_refuseUnCodeSansEmpreinteNiTexte() {
        UUID admin = persistUser().getId();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO wallet_topup_codes (id, amount, currency, max_redemptions, "
                        + "redeemed_count, expires_at, status, created_by, created_at, updated_at) "
                        + "VALUES (?, 15, 'EUR', 5, 0, ?, 'ACTIVE', ?, now(), now())",
                UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC).plusDays(5), admin))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private WalletTopupCodeEntity createCode(int maxRedemptions) {
        return createCode(maxRedemptions, persistUser().getId());
    }

    private WalletTopupCodeEntity createCode(int maxRedemptions, UUID adminId) {
        String code = "IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return adminService.create(new WalletTopupCodeAdminService.CreateCommand(code, new BigDecimal("15"), "EUR",
                maxRedemptions, LocalDateTime.now(ZoneOffset.UTC).plusDays(5), "IT", adminId));
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("topup-code-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private BigDecimal balance(UUID userId) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, "EUR").orElseThrow().getBalance();
    }

    private int ledgerRows(UUID userId) {
        return count("SELECT count(*) FROM wallet_transactions WHERE user_id = ?", userId);
    }

    private int redemptionRows(UUID codeId) {
        return count("SELECT count(*) FROM wallet_topup_code_redemptions WHERE code_id = ?", codeId);
    }

    private int redeemedCount(UUID codeId) {
        return codeRepository.findById(codeId).orElseThrow().getRedeemedCount();
    }

    private int count(String sql, UUID id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    private static void assertProblem(Throwable t, String code) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException e = (YadonyBusinessException) t;
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo(code);
    }
}
