package com.yadony.api.payments.wallet;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentCommand;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentResult;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Double validation des corrections de solde contre une vraie base PostgreSQL : le profil
 * « test » (H2, {@code ddl-auto: create}) n'applique pas les migrations, donc ni la contrainte
 * « second admin » de V359 ni l'annulation réelle d'une approbation refusée. Décor
 * {@code EmbeddedPostgres} + profil « e2e » repris de {@link WalletRefundIT}.
 *
 * <p>Pas de {@code @Transactional} sur la classe : {@code WalletService#getOrCreate} lit hors
 * transaction et ne verrait pas les écritures non committées d'une transaction de test.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class WalletAdjustmentApprovalIT {

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

    @Autowired WalletAdminAdjustmentService adjustmentService;
    @Autowired WalletAdjustmentRequestRepository requestRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-adjust-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    private static AdjustmentCommand credit(UUID userId, UUID adminId, String amount) {
        return new AdjustmentCommand(userId, adminId, "EUR", "CREDIT", new BigDecimal(amount),
                "Geste commercial après litige", "it-" + UUID.randomUUID());
    }

    private BigDecimal balanceOf(UUID userId) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, "EUR")
                .map(WalletAccountEntity::getBalance).orElse(BigDecimal.ZERO);
    }

    private WalletAdjustmentRequestStatus statusOf(UUID requestId) {
        return requestRepository.findById(requestId).orElseThrow().getStatus();
    }

    private static YadonyBusinessException business(Throwable t) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        return (YadonyBusinessException) t;
    }

    @Test
    void auteur_retireSaPropreDemande_rejetEnregistre() {
        UUID userId = persistUser();
        UUID author = UUID.randomUUID();
        AdjustmentResult opened = adjustmentService.adjust(credit(userId, author, "150.00"));
        assertThat(opened.awaitingApproval()).isTrue();

        WalletAdjustmentRequestEntity rejected = adjustmentService.reject(opened.pendingRequest().getId(), author,
                "Saisie en double");

        assertThat(rejected.getStatus()).isEqualTo(WalletAdjustmentRequestStatus.REJECTED);
        assertThat(rejected.getDecidedBy()).isEqualTo(author);
        assertThat(balanceOf(userId)).isEqualByComparingTo("0");
    }

    @Test
    void secondAdmin_approuve_mouvementEcritUneSeuleFoisAuNomDeLAuteur() {
        UUID userId = persistUser();
        UUID author = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        AdjustmentCommand command = credit(userId, author, "150.00");
        UUID requestId = adjustmentService.adjust(command).pendingRequest().getId();
        assertThat(balanceOf(userId)).isEqualByComparingTo("0");

        assertThatThrownBy(() -> adjustmentService.approve(requestId, author))
                .satisfies(t -> assertThat(business(t).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        AdjustmentResult approved = adjustmentService.approve(requestId, approver);

        assertThat(balanceOf(userId)).isEqualByComparingTo("150.00");
        assertThat(approved.transaction().getAdminActorId()).isEqualTo(author);
        WalletAdjustmentRequestEntity decided = requestRepository.findById(requestId).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(WalletAdjustmentRequestStatus.APPROVED);
        assertThat(decided.getDecidedBy()).isEqualTo(approver);
        assertThat(decided.getTransactionId()).isEqualTo(approved.transaction().getId());

        assertThatThrownBy(() -> adjustmentService.approve(requestId, UUID.randomUUID()))
                .satisfies(t -> assertThat(business(t).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        AdjustmentResult replay = adjustmentService.adjust(command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().getId()).isEqualTo(approved.transaction().getId());
        assertThat(balanceOf(userId)).isEqualByComparingTo("150.00");
    }

    @Test
    void base_refuseUneApprobationParSonAuteur() {
        UUID userId = persistUser();
        UUID author = UUID.randomUUID();
        UUID requestId = adjustmentService.adjust(credit(userId, author, "150.00")).pendingRequest().getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE wallet_adjustment_requests SET status = 'APPROVED', decided_by = requested_by WHERE id = ?",
                requestId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(statusOf(requestId)).isEqualTo(WalletAdjustmentRequestStatus.PENDING);
    }

    @Test
    void approbation_auDelaDuPlafond24h_annuleeEtDemandeResteEnAttente() {
        UUID userId = persistUser();
        UUID author = UUID.randomUUID();
        adjustmentService.adjust(credit(userId, author, "100.00"));
        UUID requestId = adjustmentService.adjust(credit(userId, author, "450.00")).pendingRequest().getId();

        assertThatThrownBy(() -> adjustmentService.approve(requestId, UUID.randomUUID()))
                .satisfies(t -> assertThat(business(t).getErrorCode())
                        .isEqualTo("wallet-adjustment-daily-cap-exceeded"));

        assertThat(statusOf(requestId)).isEqualTo(WalletAdjustmentRequestStatus.PENDING);
        assertThat(requestRepository.findById(requestId).orElseThrow().getDecidedBy()).isNull();
        assertThat(balanceOf(userId)).isEqualByComparingTo("100.00");
        assertThat(walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, "EUR")).hasSize(1);
    }
}
