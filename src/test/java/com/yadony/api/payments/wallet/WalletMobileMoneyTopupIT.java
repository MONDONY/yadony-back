package com.yadony.api.payments.wallet;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import com.yadony.api.payments.wallet.dto.WalletTopupResponse;
import com.yadony.api.payments.wallet.dto.WalletTopupStatusResponse;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Parcours complet d'une recharge du portefeuille par mobile money, de l'initiation au crédit,
 * contre une VRAIE base PostgreSQL (migrations Flyway comprises) et sans aucun mock du wallet.
 *
 * <p>Classe dédiée plutôt qu'un test de plus dans {@code WalletRefundIT} : ce parcours exige un
 * {@code PawapayClient} doublé et {@code yadony.pawapay.enabled=true}, que le profil « e2e » ne
 * pose pas — les greffer sur {@code WalletRefundIT} changerait le décor de tous ses tests de
 * remboursement pour rien.
 *
 * <p>Le décor H2 de {@code WalletControllerIT} ne conviendrait pas davantage : {@code
 * WalletService#credit} verrouille en {@code FOR NO KEY UPDATE} (refusé par H2, même en
 * {@code MODE=PostgreSQL}) et l'index unique partiel {@code uq_pawapay_ops_live_wallet_topup}
 * n'existe que dans les vraies migrations.
 *
 * <p>Pas de {@code @Transactional} sur la classe, pour deux raisons : {@code
 * WalletService#getOrCreate} est en {@code Propagation.NOT_SUPPORTED} et ne verrait pas les
 * écritures non committées d'une transaction de test, et le crédit passe par un écouteur
 * {@code AFTER_COMMIT} qui ne se déclencherait jamais.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class WalletMobileMoneyTopupIT {

    private static final String RAW_PHONE = "+225 07 34 56 78 90";
    private static final String MSISDN = "2250734567890";
    private static final String PROVIDER = "ORANGE_CIV";
    private static final String CURRENCY = "XOF";

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
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
        // Le rail est fermé par défaut (application.yml) : sans ce drapeau, initiate refuse
        // tout en 422 « mobile-money-disabled » avant même de toucher au résolveur.
        registry.add("yadony.pawapay.enabled", () -> true);
        // Aucun planificateur pawaPay pendant le test : le poller irait interroger le client
        // doublé et brouillerait les statuts qu'on vérifie (« - » = cron désactivé).
        registry.add("yadony.pawapay.poll-cron", () -> "-");
        registry.add("yadony.pawapay.balance-cron", () -> "-");
        registry.add("yadony.pawapay.deadline-cron", () -> "-");
    }

    @Autowired WalletMobileMoneyTopupService topupService;
    @Autowired WalletService walletService;
    @Autowired WalletSelfRefundService walletSelfRefundService;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired UserRepository userRepository;
    @Autowired PawapayOperationService pawapayOperations;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean PawapayClient pawapayClient;

    /** Opérateur ivoirien ouvert au dépôt, autorisation par code PIN (jamais par redirection). */
    private static PawapayProviderConfig orangeCiv() {
        return new PawapayProviderConfig(PROVIDER, "CIV", CURRENCY,
                new PawapayProviderConfig.Limits(new BigDecimal("500"), new BigDecimal("1000000"),
                        "PIN_AUTH", "OPERATIONAL"),
                null);
    }

    @BeforeEach
    void stubPawapay() {
        when(pawapayClient.predictProvider(anyString()))
                .thenReturn(Optional.of(new PawapayProviderPrediction("CIV", PROVIDER, MSISDN)));
        when(pawapayClient.activeConfiguration()).thenReturn(Map.of(PROVIDER, orangeCiv()));
        when(pawapayClient.initiateDeposit(any(PawapayDepositRequest.class)))
                .thenReturn(PawapayInitiationResult.accepted());
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-topup-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    private static WalletTopupRequest topupRequest(String amount) {
        WalletTopupRequest request = new WalletTopupRequest();
        request.setPaymentMethod("MOBILE_MONEY");
        request.setAmount(new BigDecimal(amount));
        // Saisie telle qu'un utilisateur la tape : c'est le serveur qui la normalise.
        request.setPhoneNumber(RAW_PHONE);
        return request;
    }

    /**
     * Le parcours entier, en un seul test parce que chaque étape n'a de sens qu'après la
     * précédente : c'est l'ENCHAÎNEMENT (rien de crédité à l'initiation, tout crédité au
     * callback, rien de plus au rejeu) qui est la propriété à prouver.
     */
    @Test
    void rechargeMobileMoney_crediteLeWalletDansLaDeviseDeLOperateurApresLeCallback() {
        UUID userId = persistUser();

        WalletTopupResponse initiated = topupService.initiate(userId, topupRequest("10000"));

        UUID topupId = initiated.getTopupId();
        assertThat(topupId).isNotNull();
        assertThat(initiated.getClientSecret()).isNull();
        assertThat(initiated.getCurrency()).isEqualTo(CURRENCY);
        assertThat(initiated.getProvider()).isEqualTo(PROVIDER);
        // PIN_AUTH et non REDIRECT_AUTH : aucune page d'opérateur à ouvrir.
        assertThat(initiated.getAuthorizationUrl()).isNull();
        // Le numéro ne ressort que masqué, jamais en clair.
        assertThat(initiated.getMsisdnMasked()).isEqualTo("+225 •••• 90");

        // Une initiation acceptée n'est pas un paiement : tant que l'opérateur n'a rien
        // confirmé, PAS UN CENTIME ne doit apparaître au portefeuille.
        assertThat(walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, CURRENCY))
                .isEmpty();
        assertThat(walletAccountRepository.findByUserIdAndCurrency(userId, CURRENCY)).isEmpty();
        assertThat(topupService.status(userId, topupId).status())
                .isEqualTo(WalletTopupStatusResponse.PENDING);

        // Callback pawaPay « COMPLETED », dans une transaction COMMITTÉE : l'écouteur qui
        // crédite est en AFTER_COMMIT, il ne se déclencherait pas autrement.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(pawapayOperations.apply(topupId, PawapayOperationStatus.COMPLETED, null, null,
                        "OP-CIV-1", null, "{\"status\":\"COMPLETED\"}",
                        PawapayOperationService.Source.CALLBACK)).isTrue());

        assertThat(walletService.getBalance(userId, CURRENCY)).isEqualByComparingTo("10000");
        List<WalletTransactionEntity> ledger =
                walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, CURRENCY);
        assertThat(ledger).singleElement().satisfies(tx -> {
            assertThat(tx.getType()).isEqualTo(WalletTransactionType.TOP_UP);
            assertThat(tx.getAmount()).isEqualByComparingTo("10000");
            assertThat(tx.getPaymentRef()).isEqualTo("pawapay:" + topupId);
        });

        WalletTopupStatusResponse confirmed = topupService.status(userId, topupId);
        assertThat(confirmed.status()).isEqualTo(WalletTopupStatusResponse.CONFIRMED);
        assertThat(confirmed.walletBalance()).isEqualByComparingTo("10000");

        // Rejeu du même callback (pawaPay rejoue), toujours en transaction committée : la
        // transition ne bouge plus, aucun événement n'est republié, et la clé d'idempotence
        // de credit() est le dernier filet. Double crédit = argent créé de rien.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(pawapayOperations.apply(topupId, PawapayOperationStatus.COMPLETED, null, null,
                        "OP-CIV-1", null, "{\"status\":\"COMPLETED\"}",
                        PawapayOperationService.Source.CALLBACK)).isFalse());

        assertThat(walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, CURRENCY))
                .hasSize(1);
        assertThat(walletService.getBalance(userId, CURRENCY)).isEqualByComparingTo("10000");

        // La recharge est de l'argent réellement encaissé : elle doit être remboursable au
        // même titre qu'une recharge par carte (lot 2), donc porter un paymentRef non vide.
        assertThat(walletSelfRefundService.allocation(userId, CURRENCY).refundableTotal())
                .isEqualByComparingTo("10000");
    }
}
