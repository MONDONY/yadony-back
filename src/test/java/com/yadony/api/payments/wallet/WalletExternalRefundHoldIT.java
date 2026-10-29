package com.yadony.api.payments.wallet;

import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentCommand;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recharge remboursée hors de l'app, contre une vraie base PostgreSQL : migration V361 (contraintes
 * comprises), gel de la devise, régularisation par débit admin, levée. Décor {@code EmbeddedPostgres}
 * + profil « e2e » repris de {@link WalletRefundIT} ; pas de {@code @Transactional} sur la classe
 * ({@code WalletService#getOrCreate} lit hors transaction).
 */
@SpringBootTest
@ActiveProfiles("e2e")
class WalletExternalRefundHoldIT {

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

    @Autowired WalletExternalRefundService externalRefundService;
    @Autowired WalletService walletService;
    @Autowired WalletSelfRefundService walletSelfRefundService;
    @Autowired WalletAdminAdjustmentService adjustmentService;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-ext-refund-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    private static Charge refundedCharge(String paymentIntentId, String refundId, long amountCents) {
        Refund refund = new Refund();
        refund.setId(refundId);
        refund.setStatus("succeeded");
        refund.setAmount(amountCents);
        RefundCollection refunds = new RefundCollection();
        refunds.setData(List.of(refund));
        refunds.setHasMore(false);
        Charge charge = new Charge();
        charge.setPaymentIntent(paymentIntentId);
        charge.setRefunds(refunds);
        return charge;
    }

    private BigDecimal balanceOf(UUID userId) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, "EUR").orElseThrow().getBalance();
    }

    private static String codeOf(Throwable t) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        return ((YadonyBusinessException) t).getErrorCode();
    }

    @Test
    void remboursementExterne_gel_regularisationParDebitAdmin_puisLevee() {
        UUID userId = persistUser();
        String pi = "pi_ext_" + UUID.randomUUID();
        walletService.credit(userId, "EUR", new BigDecimal("40.00"), WalletTransactionType.TOP_UP, pi,
                "k-" + pi);
        String refundId = "re_ext_" + UUID.randomUUID();

        assertThat(externalRefundService.recordFromCharge(refundedCharge(pi, refundId, 2500))).isEqualTo(1);
        assertThat(externalRefundService.recordFromCharge(refundedCharge(pi, refundId, 2500))).isZero();

        assertThat(walletService.isFrozen(userId, "EUR")).isTrue();
        assertThatThrownBy(() -> walletService.debit(userId, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, UUID.randomUUID()))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("wallet-external-refund-hold"));
        assertThatThrownBy(() -> walletSelfRefundService.request(userId, "EUR", List.of()))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("wallet-external-refund-hold"));

        UUID admin = UUID.randomUUID();
        assertThatThrownBy(() -> adjustmentService.adjust(new AdjustmentCommand(userId, admin, "EUR", "CREDIT",
                new BigDecimal("5.00"), "Geste commercial après litige", "k-" + UUID.randomUUID())))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("wallet-external-refund-hold"));
        adjustmentService.adjust(new AdjustmentCommand(userId, admin, "EUR", "DEBIT", new BigDecimal("25.00"),
                "Recharge remboursée hors de l'app (Stripe)", "k-" + UUID.randomUUID()));
        assertThat(balanceOf(userId)).isEqualByComparingTo("15.00");

        WalletExternalRefundHoldEntity hold = externalRefundService.holds(userId).get(0);
        assertThat(hold.getAmount()).isEqualByComparingTo("25.00");
        WalletExternalRefundHoldEntity released = externalRefundService.release(userId, hold.getId(), admin,
                "Solde débité de 25 €");
        assertThat(released.getStatus()).isEqualTo(WalletExternalRefundHoldStatus.RELEASED);
        assertThat(released.getReleasedBy()).isEqualTo(admin);
        assertThat(walletService.isFrozen(userId, "EUR")).isFalse();
        assertThatThrownBy(() -> externalRefundService.release(userId, hold.getId(), admin, "encore"))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("wallet-external-refund-hold-already-released"));
    }

    @Test
    void base_refuseUneLeveeSansAuteurNiNote() {
        UUID userId = persistUser();
        String pi = "pi_ext_" + UUID.randomUUID();
        walletService.credit(userId, "EUR", new BigDecimal("10.00"), WalletTransactionType.TOP_UP, pi, "k-" + pi);
        externalRefundService.recordFromCharge(refundedCharge(pi, "re_ext_" + UUID.randomUUID(), 1000));
        UUID holdId = externalRefundService.holds(userId).get(0).getId();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE wallet_external_refund_holds SET status = 'RELEASED' WHERE id = ?", holdId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(walletService.isExternalRefundHeld(userId, "EUR")).isTrue();
    }
}
