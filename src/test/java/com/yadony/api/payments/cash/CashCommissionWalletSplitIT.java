package com.yadony.api.payments.cash;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant « tout ou rien » de la répartition d'une commission espèces entre le
 * portefeuille de la devise du colis et celui de la devise active, contre une VRAIE base
 * PostgreSQL : {@code CashCommissionServiceTest} mocke {@code WalletService}, il ne prouve
 * donc ni le verrou pessimiste pris par {@code WalletService.getBalanceForUpdate} ni ce qui
 * reste effectivement en base après un refus.
 *
 * <p>Décor {@code EmbeddedPostgres} + profil {@code e2e} comme {@code WalletRefundIT} :
 * {@code @Lock(PESSIMISTIC_WRITE)} se traduit en {@code FOR NO KEY UPDATE}, syntaxe que H2
 * refuse même en {@code MODE=PostgreSQL}. La classe est {@code @Transactional} : chaque test
 * tourne dans une transaction annulée à la fin, que {@code acceptCashBid} rejoint — c'est
 * bien la transaction de l'appelant dans laquelle {@code plan} verrouille puis débite.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class CashCommissionWalletSplitIT {

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

    @Autowired private CashCommissionService cashCommissionService;
    @Autowired private ExchangeRateService exchangeRateService;
    @Autowired private ExchangeRateRepository exchangeRateRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private UUID travelerId;
    private UUID bidId;
    private AnnouncementEntity announcement;
    private BidEntity bid;

    @BeforeEach
    void seed() {
        seedRate("XOF", "655.957");
        travelerId = persistUser();
        UUID senderId = persistUser();

        // Colis en XOF : 5 kg × 1750 XOF/kg = 8750 XOF de brut ; la commission au taux
        // plateforme (plancher 1 EUR ≈ 656 XOF) dépasse toujours les 600 XOF du portefeuille
        // XOF, un complément sur l'actif (EUR) est donc toujours nécessaire.
        announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        announcement.setDepartureDate(LocalDate.now().plusDays(10));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel("Gare du Nord, Paris");
        announcement.setPickupLat(new BigDecimal("48.880756"));
        announcement.setPickupLng(new BigDecimal("2.354987"));
        announcement.setDeliveryAddressLabel("Aéroport de Dakar");
        announcement.setDeliveryLat(new BigDecimal("14.670833"));
        announcement.setDeliveryLng(new BigDecimal("-17.073056"));
        announcement.setAvailableKg(new BigDecimal("20.00"));
        announcement.setTotalKg(new BigDecimal("23.00"));
        announcement.setPricePerKg(new BigDecimal("1750"));
        announcement.setCurrency("XOF");
        announcement.setTimezone("Europe/Paris");
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        entityManager.persist(announcement);

        bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(senderId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCurrency("XOF");
        bid.setWeightKg(new BigDecimal("5"));
        bid.setStatus(BidStatus.PENDING);
        entityManager.persist(bid);
        entityManager.flush();
        bidId = bid.getId();
    }

    @Test
    void notCovered_refusesWithoutAnyCommissionLine_bothWalletsIntact() {
        // 600 XOF + 0,05 EUR (≈ 33 XOF) ne couvrent jamais la commission (≥ 656 XOF).
        openWallet(travelerId, "XOF", new BigDecimal("600"));
        openWallet(travelerId, "EUR", new BigDecimal("0.05"));

        AcceptBidResponse r = cashCommissionService.acceptCashBid(bidId, travelerId, CommissionSource.WALLET_FIRST);

        entityManager.flush();
        entityManager.clear();
        BigDecimal commission = commissionOf(reread(BidEntity.class, bidId));
        assertThat(r.status()).isEqualTo(AcceptanceStatusDto.INSUFFICIENT_WALLET);
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.availableBalance()).isEqualByComparingTo("0.05");
        assertThat(r.requiredCommission()).isEqualByComparingTo(exchangeRateService.convert(commission, "XOF", "EUR"));
        assertThat(r.breakdown()).isNotNull();
        assertThat(r.breakdown().bidCurrency()).isEqualTo("XOF");
        assertThat(r.breakdown().coveredByBidWallet()).isEqualByComparingTo("600");
        assertThat(r.breakdown().remainingBid()).isEqualByComparingTo(commission.subtract(new BigDecimal("600")));
        assertThat(r.breakdown().activeBalance()).isEqualByComparingTo("0.05");

        // AUCUNE ligne COMMISSION_DEDUCTED : pas même sur le portefeuille XOF partiellement suffisant.
        assertThat(walletTransactionRepository.findAllByUserIdAndBidIdAndType(
                travelerId, bidId, WalletTransactionType.COMMISSION_DEDUCTED)).isEmpty();
        assertThat(commissionLines(travelerId, "XOF")).isEmpty();
        assertThat(commissionLines(travelerId, "EUR")).isEmpty();
        assertThat(balance(travelerId, "XOF")).isEqualByComparingTo("600");
        assertThat(balance(travelerId, "EUR")).isEqualByComparingTo("0.05");
        BidEntity after = reread(BidEntity.class, bidId);
        assertThat(after.getStatus()).isEqualTo(BidStatus.PENDING);
        assertThat(after.getCommissionStatus()).isNotEqualTo(CommissionStatus.CHARGED);
    }

    @Test
    void covered_debitsBidWalletThenConvertedRemainderOnActive_underLock() {
        // 600 XOF + 2,00 EUR (≈ 1312 XOF) couvrent toujours la commission (≤ 1750 XOF).
        openWallet(travelerId, "XOF", new BigDecimal("600"));
        openWallet(travelerId, "EUR", new BigDecimal("2.00"));

        AcceptBidResponse r = cashCommissionService.acceptCashBid(bidId, travelerId, CommissionSource.WALLET_FIRST);

        entityManager.flush();
        entityManager.clear();
        assertThat(r.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        BidEntity after = reread(BidEntity.class, bidId);
        assertThat(after.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(after.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        assertThat(after.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);

        BigDecimal commission = commissionOf(after);
        BigDecimal remainingXof = commission.subtract(new BigDecimal("600"));
        BigDecimal remainingEur = exchangeRateService.convert(remainingXof, "XOF", "EUR");

        List<WalletTransactionEntity> xof = commissionLines(travelerId, "XOF");
        assertThat(xof).hasSize(1);
        assertThat(xof.get(0).getAmount()).isEqualByComparingTo("-600");
        assertThat(xof.get(0).getBidId()).isEqualTo(bidId);
        assertThat(xof.get(0).getSourceCurrency()).isNull();

        List<WalletTransactionEntity> eur = commissionLines(travelerId, "EUR");
        assertThat(eur).hasSize(1);
        assertThat(eur.get(0).getAmount()).isEqualByComparingTo(remainingEur.negate());
        assertThat(eur.get(0).getBidId()).isEqualTo(bidId);
        assertThat(eur.get(0).getSourceCurrency()).isEqualTo("XOF");
        assertThat(eur.get(0).getSourceAmount()).isEqualByComparingTo(remainingXof);
        assertThat(eur.get(0).getAppliedRate()).isNotNull();

        assertThat(balance(travelerId, "XOF")).isEqualByComparingTo("0");
        assertThat(balance(travelerId, "EUR")).isEqualByComparingTo(new BigDecimal("2.00").subtract(remainingEur));
    }

    @Test
    void cancelled_afterTwoLineSplit_recreditsBothWalletsAndRestoresBalances() {
        // Suite du scénario ci-dessus (répartition sur les deux portefeuilles) : Task 3
        // doit recréditer CHAQUE ligne COMMISSION_DEDUCTED dans sa devise à l'annulation,
        // pas seulement la première trouvée (IncorrectResultSizeDataAccessException sinon).
        openWallet(travelerId, "XOF", new BigDecimal("600"));
        openWallet(travelerId, "EUR", new BigDecimal("2.00"));

        AcceptBidResponse r = cashCommissionService.acceptCashBid(bidId, travelerId, CommissionSource.WALLET_FIRST);
        entityManager.flush();
        entityManager.clear();
        assertThat(r.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);

        BidEntity accepted = reread(BidEntity.class, bidId);
        BigDecimal commission = commissionOf(accepted);
        BigDecimal remainingXof = commission.subtract(new BigDecimal("600"));
        BigDecimal remainingEur = exchangeRateService.convert(remainingXof, "XOF", "EUR");

        // Sanity : la répartition en deux lignes a bien eu lieu avant l'annulation.
        assertThat(commissionLines(travelerId, "XOF")).hasSize(1);
        assertThat(commissionLines(travelerId, "EUR")).hasSize(1);

        // BidCancelledCommissionRefundListener écoute BidRejectedEvent en AFTER_COMMIT ;
        // cette classe de test est @Transactional (rollback en fin de test, jamais de
        // commit réel), donc le listener ne se déclencherait pas ici. On appelle
        // directement le service avec la clé exacte que le listener utilise.
        cashCommissionService.refundCommissionToWallet(accepted, travelerId, "wallet-refund-cancel-" + bidId);

        entityManager.flush();
        entityManager.clear();

        BidEntity afterRefund = reread(BidEntity.class, bidId);
        assertThat(afterRefund.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);

        List<WalletTransactionEntity> xofRefunds = refundLines(travelerId, "XOF");
        assertThat(xofRefunds).hasSize(1);
        assertThat(xofRefunds.get(0).getAmount()).isEqualByComparingTo("600");

        List<WalletTransactionEntity> eurRefunds = refundLines(travelerId, "EUR");
        assertThat(eurRefunds).hasSize(1);
        assertThat(eurRefunds.get(0).getAmount()).isEqualByComparingTo(remainingEur);

        assertThat(balance(travelerId, "XOF")).isEqualByComparingTo("600");
        assertThat(balance(travelerId, "EUR")).isEqualByComparingTo("2.00");
    }

    // --- helpers ---

    private void seedRate(String currency, String unitsPerEur) {
        exchangeRateRepository.save(new ExchangeRateEntity(currency, new BigDecimal(unitsPerEur)));
        var cache = cacheManager.getCache("exchange-rates");
        if (cache != null) {
            cache.evict(currency);
        }
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("commission-split-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    private void openWallet(UUID userId, String currency, BigDecimal balance) {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency(currency);
        wallet.setBalance(balance);
        walletAccountRepository.saveAndFlush(wallet);
    }

    /** Commission attendue au taux réellement figé sur le bid (plancher 1 EUR mis à l'échelle XOF). */
    private BigDecimal commissionOf(BidEntity persisted) {
        assertThat(persisted.getCommissionRate()).isNotNull();
        return cashCommissionService.computeCommission(
                new BigDecimal("8750"), persisted.getCommissionRate(), SupportedCurrency.XOF);
    }

    private <T> T reread(Class<T> type, UUID id) {
        return entityManager.find(type, id);
    }

    private BigDecimal balance(UUID userId, String currency) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, currency).orElseThrow().getBalance();
    }

    private List<WalletTransactionEntity> commissionLines(UUID userId, String currency) {
        return walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, currency).stream()
                .filter(tx -> tx.getType() == WalletTransactionType.COMMISSION_DEDUCTED)
                .toList();
    }

    private List<WalletTransactionEntity> refundLines(UUID userId, String currency) {
        return walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, currency).stream()
                .filter(tx -> tx.getType() == WalletTransactionType.REFUND)
                .toList();
    }
}
