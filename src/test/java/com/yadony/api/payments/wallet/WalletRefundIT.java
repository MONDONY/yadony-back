package com.yadony.api.payments.wallet;

import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Parcours de remboursement wallet contre une vraie base PostgreSQL et sans aucun mock de
 * {@link WalletService}. Le décor de {@code WalletControllerIT} (H2, profil « test ») ne
 * convient pas ici : {@code debitConfirmedRefund} verrouille via
 * {@code findByUserIdAndCurrencyForUpdate}, qu'Hibernate traduit en {@code FOR NO KEY UPDATE}
 * — syntaxe qu'H2 refuse, même en {@code MODE=PostgreSQL}. On reprend donc le décor
 * {@code EmbeddedPostgres} + profil « e2e » de {@link WalletServiceIT}, qui applique en plus
 * les vraies migrations Flyway (contraintes CHECK et index uniques partiels compris).
 *
 * <p>Pas de {@code @Transactional} sur la classe : {@code WalletService#getOrCreate} est en
 * {@code Propagation.NOT_SUPPORTED} et lit donc sur une connexion hors transaction, qui ne
 * verrait pas les écritures non committées d'une transaction de test.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class WalletRefundIT {

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

    @Autowired WalletRefundRequestService walletRefundRequestService;
    @Autowired WalletSelfRefundService walletSelfRefundService;
    @Autowired WalletService walletService;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired WalletRefundRequestRepository walletRefundRequestRepository;
    @Autowired WalletRefundRequestItemRepository walletRefundRequestItemRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired WalletRefundIssueRecoveryScheduler recoveryScheduler;
    @Autowired PlatformTransactionManager transactionManager;

    /** Refund renvoyé par le Refund.create simulé, d'identifiant {@code id}. */
    private static Refund stripeRefund(String id) {
        Refund refund = new Refund();
        refund.setId(id);
        refund.setStatus("pending");
        return refund;
    }

    private UUID topUp(UUID userId, String amount, String paymentIntentId) {
        walletService.credit(userId, "EUR", new BigDecimal(amount),
                WalletTransactionType.TOP_UP, paymentIntentId, "k-" + paymentIntentId + "-" + UUID.randomUUID());
        return walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, "EUR").stream()
                .filter(t -> paymentIntentId.equals(t.getPaymentRef()))
                .findFirst().orElseThrow().getId();
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-refund-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    /** Utilisateur déjà finalisé : ce que fait {@code AccountFinalizationService#finalize} en base. */
    private UUID persistFinalizedUser() {
        UUID userId = persistUser();
        UserEntity user = userRepository.findById(userId).orElseThrow();
        user.setStatus(UserStatus.BANNED);
        user.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRepository.saveAndFlush(user);
        return userId;
    }

    private BigDecimal balanceOf(UUID userId) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, "EUR").orElseThrow().getBalance();
    }

    private WalletRefundRequestEntity saveRequest(UUID userId, String amount, WalletRefundChannel channel,
                                                  WalletRefundRequestStatus status, UUID parentRequestId) {
        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(userId);
        request.setCurrency("EUR");
        request.setAmount(new BigDecimal(amount));
        request.setChannel(channel);
        request.setStatus(status);
        request.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        request.setParentRequestId(parentRequestId);
        return walletRefundRequestRepository.saveAndFlush(request);
    }

    @Test
    void request_emetLeRemboursementStripeApresLeCommitDeLaDemande() {
        UUID userId = persistUser();
        String pi = "pi_after_commit_" + UUID.randomUUID();
        topUp(userId, "25.00", pi);

        WalletRefundRequestEntity request;
        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(stripeRefund("re_after_commit"));

            request = walletSelfRefundService.request(userId, "EUR", List.of());

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)));
        }

        // Le contrat de la réponse ne change pas : la demande renvoyée est PROCESSING.
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.PROCESSING);
        List<WalletRefundRequestItemEntity> items = walletRefundRequestItemRepository.findByRefundRequestId(request.getId());
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
            assertThat(item.getStripeRefundId()).isEqualTo("re_after_commit");
        });
    }

    @Test
    void request_dansUneTransactionEnglobanteAnnulee_nAppelleJamaisStripe() {
        UUID userId = persistUser();
        String pi = "pi_rollback_" + UUID.randomUUID();
        topUp(userId, "25.00", pi);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                walletSelfRefundService.request(userId, "EUR", List.of());
                status.setRollbackOnly();
            });

            refundStatic.verifyNoInteractions();
        }
        assertThat(walletRefundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(userId)).isEmpty();
        assertThat(balanceOf(userId)).isEqualByComparingTo("25.00");
    }

    @Test
    void reprise_emetUnItemPendingResteNonEmis() {
        UUID userId = persistUser();
        String pi = "pi_recovery_" + UUID.randomUUID();
        UUID topupId = topUp(userId, "18.00", pi);
        WalletRefundRequestEntity request = saveRequest(userId, "18.00",
                WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundRequestStatus.PROCESSING, null);
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(request.getId());
        item.setWalletTransactionId(topupId);
        item.setPaymentIntentId(pi);
        item.setAmount(new BigDecimal("18.00"));
        item.setStatus(WalletRefundItemStatus.PENDING);
        walletRefundRequestItemRepository.saveAndFlush(item);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(stripeRefund("re_recovery"));

            // Date limite dans le futur : l'item vient d'être créé, on simule son ancienneté.
            recoveryScheduler.recoverItemsCreatedBefore(Instant.now().plusSeconds(60));
        }

        WalletRefundRequestItemEntity reloaded = walletRefundRequestItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        assertThat(reloaded.getStripeRefundId()).isEqualTo("re_recovery");
    }

    @Test
    void suppressionDeCompte_emetAuCommitDuReglementAvantLaFinDeLaTransactionEnglobante() {
        UUID userId = persistUser();
        String pi = "pi_deletion_" + UUID.randomUUID();
        topUp(userId, "30.00", pi);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(stripeRefund("re_deletion"));

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                List<WalletRefundRequestEntity> opened = userService.settleWalletsForDeletion(userId);

                // Toujours dans la transaction de suppression (finalisation pas encore faite) :
                // le règlement, en REQUIRES_NEW, a committé et son écouteur a déjà émis.
                refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)));
                assertThat(opened).hasSize(1);
                assertThat(walletRefundRequestItemRepository.findByRefundRequestId(opened.get(0).getId()))
                        .singleElement()
                        .satisfies(item -> assertThat(item.getStripeRefundId()).isEqualTo("re_deletion"));
                // La finalisation échoue : la transaction englobante est annulée.
                status.setRollbackOnly();
            });
        }

        // Le remboursement Stripe est parti : sa trace en base survit à l'annulation.
        assertThat(walletRefundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(userId))
                .singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(WalletRefundRequestStatus.PROCESSING));
    }

    @Test
    void resolve_ticketManuelPending_debiteMalgreLeGelQueLeTicketProvoqueLuiMeme() {
        // Régression : resolve() passait par walletService.debit, donc par assertNotFrozen,
        // que le ticket PENDING en cours de résolution déclenche lui-même. Tout ticket MANUAL
        // était par construction irrésoluble (422 wallet-refund-pending systématique).
        UUID userId = persistUser();
        walletService.credit(userId, "EUR", new BigDecimal("45.00"),
                WalletTransactionType.TOP_UP, "pi_manual_it", "k-manual-" + UUID.randomUUID());
        WalletRefundRequestEntity ticket = saveRequest(userId, "45.00",
                WalletRefundChannel.MANUAL_ADMIN, WalletRefundRequestStatus.PENDING, null);

        WalletRefundRequestEntity resolved =
                walletRefundRequestService.resolve(ticket.getId(), UUID.randomUUID());

        assertThat(resolved.getStatus()).isEqualTo(WalletRefundRequestStatus.RESOLVED);
        assertThat(balanceOf(userId)).isEqualByComparingTo("0.00");
        assertThat(walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, "EUR"))
                .anyMatch(t -> t.getType() == WalletTransactionType.ADMIN_REFUND_OUT
                        && t.getAmount().compareTo(new BigDecimal("-45.00")) == 0);
    }

    @Test
    void resolve_ticketEnfant_neDebiteQueSaPartCashEtLaisseLeNonCash() {
        UUID userId = persistUser();
        // 30 de recharge carte (dont 12 en échec Stripe, repris par le ticket enfant)
        // et 10 de parrainage : seuls les 12 du ticket doivent partir.
        walletService.credit(userId, "EUR", new BigDecimal("30.00"),
                WalletTransactionType.TOP_UP, "pi_child_it", "k-child-" + UUID.randomUUID());
        walletService.credit(userId, "EUR", new BigDecimal("10.00"),
                WalletTransactionType.REFERRAL_REWARD, null, "k-child2-" + UUID.randomUUID());
        WalletRefundRequestEntity parent = saveRequest(userId, "30.00",
                WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundRequestStatus.FAILED, null);
        WalletRefundRequestEntity child = saveRequest(userId, "12.00",
                WalletRefundChannel.MANUAL_ADMIN, WalletRefundRequestStatus.PENDING, parent.getId());

        walletRefundRequestService.resolve(child.getId(), UUID.randomUUID());

        assertThat(balanceOf(userId)).isEqualByComparingTo("28.00");
    }

    @Test
    void handleChargeRefunded_apresFinalisationDuCompte_debiteLeWalletEtResoutLaDemande() {
        // Le webhook Stripe arrive typiquement après la finalisation du compte : plus aucun
        // utilisateur visible, mais le wallet et la demande doivent encore se régler.
        UUID userId = persistFinalizedUser();
        walletService.credit(userId, "EUR", new BigDecimal("35.00"),
                WalletTransactionType.TOP_UP, "pi_x", "k-finalized-" + UUID.randomUUID());
        WalletTransactionEntity topup = walletTransactionRepository
                .findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, "EUR").get(0);

        WalletRefundRequestEntity request = saveRequest(userId, "35.00",
                WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundRequestStatus.PROCESSING, null);
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(request.getId());
        item.setWalletTransactionId(topup.getId());
        item.setPaymentIntentId("pi_x");
        item.setStripeRefundId("re_x");
        item.setAmount(new BigDecimal("35.00"));
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        walletRefundRequestItemRepository.saveAndFlush(item);

        Charge charge = new Charge();
        charge.setPaymentIntent("pi_x");
        charge.setAmount(3500L);
        charge.setAmountRefunded(3500L);
        Refund refund = new Refund();
        refund.setId("re_x");
        refund.setStatus("succeeded");
        RefundCollection refunds = new RefundCollection();
        refunds.setData(List.of(refund));
        charge.setRefunds(refunds);

        walletSelfRefundService.handleChargeRefunded(charge);

        assertThat(balanceOf(userId)).isEqualByComparingTo("0.00");
        assertThat(walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, "EUR"))
                .anyMatch(t -> t.getType() == WalletTransactionType.SELF_REFUND_OUT
                        && t.getAmount().compareTo(new BigDecimal("-35.00")) == 0);
        assertThat(walletRefundRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(WalletRefundRequestStatus.REFUNDED);
        assertThat(walletRefundRequestItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(WalletRefundItemStatus.REFUNDED);
    }
}
