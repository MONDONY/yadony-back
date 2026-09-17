package com.yadony.api.payments.wallet;

import com.stripe.exception.InvalidRequestException;
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
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Autowired PawapayOperationService pawapayOperations;

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

    private WalletRefundRequestItemEntity saveItem(UUID requestId, UUID topupId, String pi, String amount,
                                                   WalletRefundItemStatus status, String stripeRefundId) {
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(requestId);
        item.setWalletTransactionId(topupId);
        item.setPaymentIntentId(pi);
        item.setAmount(new BigDecimal(amount));
        item.setStatus(status);
        item.setStripeRefundId(stripeRefundId);
        return walletRefundRequestItemRepository.saveAndFlush(item);
    }

    @Test
    void echecStripe_rechargeFraiche_resolutionDuTicketEnfant_consommeLaBonneRecharge() {
        UUID userId = persistUser();
        String piA = "pi_child_a_" + UUID.randomUUID();
        String piB = "pi_child_b_" + UUID.randomUUID();
        UUID topupA = topUp(userId, "30.00", piA);

        WalletRefundRequestEntity parent;
        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("resource_missing", "payment_intent", "req_it",
                            "resource_missing", 400, null));

            parent = walletSelfRefundService.request(userId, "EUR", List.of());
        }

        // Tout a échoué à l'émission : la demande est close et le ticket enfant porte l'item.
        assertThat(walletRefundRequestRepository.findById(parent.getId()).orElseThrow().getStatus())
                .isEqualTo(WalletRefundRequestStatus.FAILED);
        assertThat(walletRefundRequestItemRepository.findByRefundRequestId(parent.getId()))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
                    assertThat(i.getFailureReason()).isEqualTo("resource_missing");
                });
        WalletRefundRequestEntity child = walletRefundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(userId)
                .stream().filter(r -> parent.getId().equals(r.getParentRequestId())).findFirst().orElseThrow();
        assertThat(child.getStatus()).isEqualTo(WalletRefundRequestStatus.PENDING);
        assertThat(walletRefundRequestItemRepository.findByRefundRequestId(child.getId()))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.getStatus()).isEqualTo(WalletRefundItemStatus.PENDING);
                    assertThat(i.getWalletTransactionId()).isEqualTo(topupA);
                    assertThat(i.getPaymentIntentId()).isEqualTo(piA);
                    assertThat(i.getAmount()).isEqualByComparingTo("30.00");
                });

        UUID topupB = topUp(userId, "20.00", piB);
        WalletRefundAllocation beforeResolution = walletSelfRefundService.allocation(userId, "EUR");
        assertThat(beforeResolution.inFlight()).isEqualByComparingTo("30.00");
        assertThat(beforeResolution.refundableTotal()).isEqualByComparingTo("20.00");

        walletRefundRequestService.resolve(child.getId(), UUID.randomUUID());

        assertThat(balanceOf(userId)).isEqualByComparingTo("20.00");
        assertThat(walletRefundRequestItemRepository.findByRefundRequestId(child.getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED));
        WalletRefundAllocation after = walletSelfRefundService.allocation(userId, "EUR");
        assertThat(after.refundable()).singleElement().satisfies(t -> {
            assertThat(t.walletTransactionId()).isEqualTo(topupB);
            assertThat(t.remaining()).isEqualByComparingTo("20.00");
        });
        assertThat(after.inFlight()).isEqualByComparingTo("0");
        assertThat(walletSelfRefundService.refundStatusByTransactionId(List.of(topupA, topupB)))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(topupA, "REFUNDED"));
    }

    @Test
    void indexPartiel_plusieursItemsParPaymentIntentMaisUnSeulActif() {
        UUID userId = persistUser();
        String pi = "pi_index_" + UUID.randomUUID();
        UUID topupId = topUp(userId, "30.00", pi);
        WalletRefundRequestEntity request = saveRequest(userId, "30.00",
                WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundRequestStatus.FAILED, null);

        saveItem(request.getId(), topupId, pi, "30.00", WalletRefundItemStatus.FAILED, "re_old");
        saveItem(request.getId(), topupId, pi, "10.00", WalletRefundItemStatus.REFUNDED, "re_old2");
        saveItem(request.getId(), topupId, pi, "30.00", WalletRefundItemStatus.PENDING, null);

        assertThatThrownBy(() -> saveItem(request.getId(), topupId, pi, "30.00",
                WalletRefundItemStatus.PROCESSING, "re_second"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void handleChargeRefunded_retrouveLItemProcessingParmiLesAnciensItemsDuPaymentIntent() {
        UUID userId = persistUser();
        String pi = "pi_webhook_" + UUID.randomUUID();
        UUID topupId = topUp(userId, "30.00", pi);
        WalletRefundRequestEntity old = saveRequest(userId, "30.00",
                WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundRequestStatus.FAILED, null);
        saveItem(old.getId(), topupId, pi, "30.00", WalletRefundItemStatus.FAILED, "re_failed");
        WalletRefundRequestEntity current = saveRequest(userId, "30.00",
                WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundRequestStatus.PROCESSING, null);
        WalletRefundRequestItemEntity processing =
                saveItem(current.getId(), topupId, pi, "30.00", WalletRefundItemStatus.PROCESSING, "re_current");

        Charge charge = new Charge();
        charge.setPaymentIntent(pi);
        Refund refund = new Refund();
        refund.setId("re_current");
        refund.setStatus("succeeded");
        RefundCollection refunds = new RefundCollection();
        refunds.setData(List.of(refund));
        charge.setRefunds(refunds);

        walletSelfRefundService.handleChargeRefunded(charge);

        assertThat(walletRefundRequestItemRepository.findById(processing.getId()).orElseThrow().getStatus())
                .isEqualTo(WalletRefundItemStatus.REFUNDED);
        assertThat(walletRefundRequestRepository.findById(current.getId()).orElseThrow().getStatus())
                .isEqualTo(WalletRefundRequestStatus.REFUNDED);
        assertThat(balanceOf(userId)).isEqualByComparingTo("0.00");
    }

    /** Recharge mobile money non terminale, telle que l'initiation la crée. */
    private PawapayOperationEntity liveTopup(UUID userId, String currency) {
        return pawapayOperations.create(PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP,
                userId, null, null, new BigDecimal("10000"), currency, "ORANGE_CIV", "CI", "2250734567890");
    }

    /**
     * Filet de la garde « une seule recharge en attente » : la vérification applicative lit
     * avant d'écrire, deux requêtes concurrentes la franchissent toutes les deux. Seul l'index
     * unique partiel {@code uq_pawapay_ops_live_wallet_topup} (V260) tranche — d'où ce test sur
     * la VRAIE base, avec les vraies migrations : sur H2 il ne prouverait rien.
     */
    @Test
    void create_uneSeuleRechargeVivanteParUtilisateurEtDevise() {
        UUID userId = persistUser();
        PawapayOperationEntity first = liveTopup(userId, "XOF");

        assertThatThrownBy(() -> liveTopup(userId, "XOF"))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessage("Une recharge est déjà en attente de validation sur votre téléphone.")
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("topup-already-pending");

        // La devise fait partie de la clé : une recharge XAF reste possible pendant qu'une
        // XOF est en attente, les deux portefeuilles étant distincts.
        assertThat(liveTopup(userId, "XAF").getId()).isNotEqualTo(first.getId());

        // Une recharge TERMINÉE ne doit jamais bloquer la suivante, sinon on ne recharge
        // qu'une fois dans sa vie.
        assertThat(pawapayOperations.apply(first.getId(), PawapayOperationStatus.COMPLETED, null, null, null, null,
                "{}", PawapayOperationService.Source.SYSTEM)).isTrue();

        assertThat(liveTopup(userId, "XOF").getId()).isNotEqualTo(first.getId());
    }
}
