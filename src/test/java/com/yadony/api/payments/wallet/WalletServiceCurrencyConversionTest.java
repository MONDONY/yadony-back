package com.yadony.api.payments.wallet;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.TransportMode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contre-partie « vraie base » de la revue de la tâche 9 : les tests de
 * {@code CashCommissionServiceTest} mockent entièrement {@link WalletService}, donc
 * aucun d'eux n'exerce réellement le SQL/JPA des deux surcharges de {@code debit(...)}
 * qui snapshotent une conversion de devise (colonnes {@code source_currency} /
 * {@code source_amount} / {@code applied_rate} de {@code wallet_transactions},
 * migration V227). Ce fichier persiste pour de vrai et relit l'entité pour prouver que
 * ces colonnes sont effectivement écrites, que la garde de solde insuffisant fonctionne
 * aussi sur ces surcharges, et que deux prélèvements successifs snapshotent chacun leur
 * propre taux sans jamais réécrire le précédent déjà persisté.
 *
 * <p>Même pattern que {@link WalletServiceIT} (PostgreSQL embarqué + vrai Flyway, profil
 * {@code e2e}) plutôt que {@code @DataJpaTest} : le profil {@code test} configure H2 avec
 * {@code ddl-auto=create} (Flyway désactivé), et
 * {@code @Lock(PESSIMISTIC_WRITE)} sur {@code findByUserIdAndCurrencyForUpdate} se traduit
 * en {@code FOR NO KEY UPDATE}, syntaxe spécifique PostgreSQL absente de H2. {@code
 * wallet_accounts.user_id} porte en outre une contrainte FK vers {@code users(id)}
 * (V113), et {@code wallet_transactions.bid_id} vers {@code bids(id)} (V114) : un
 * {@code UUID.randomUUID()} nu ne suffit pas pour ni l'un ni l'autre, il faut des
 * lignes réelles persistées, d'où {@link #persistUser()} et {@link #persistBid()}
 * (qui persiste au passage l'annonce et les deux utilisateurs traveler/sender requis
 * par les FK de {@code bids}).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class WalletServiceCurrencyConversionTest {

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

    @Autowired private WalletService walletService;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("wallet-currency-conversion-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    private UUID openWallet(String currency, BigDecimal balance) {
        UUID userId = persistUser();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency(currency);
        wallet.setBalance(balance);
        walletAccountRepository.saveAndFlush(wallet);
        return userId;
    }

    /**
     * Bid minimal viable pour satisfaire {@code wallet_transactions_bid_id_fkey}
     * (V114 : {@code bid_id} référence {@code bids(id)}), lui-même dépendant d'une
     * announcement valide (V3 : {@code fk_bids_announcement}, {@code fk_bids_sender},
     * {@code fk_announcements_traveler}) — donc de deux utilisateurs réels
     * supplémentaires (traveler, sender). Seuls {@code announcementId} et
     * {@code senderId} sont NOT NULL sans défaut côté {@link BidEntity} ; le reste
     * (poids, devise, statut...) n'a aucun rapport avec ce que ces tests vérifient.
     */
    private UUID persistBid() {
        UUID travelerId = persistUser();
        UUID senderId = persistUser();

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Bamako");
        announcement.setDepartureDate(LocalDate.of(2026, 8, 15));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel("Gare du Nord, Paris");
        announcement.setPickupLat(new BigDecimal("48.880756"));
        announcement.setPickupLng(new BigDecimal("2.354987"));
        announcement.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        announcement.setDeliveryLat(new BigDecimal("12.533579"));
        announcement.setDeliveryLng(new BigDecimal("-7.948969"));
        announcement.setAvailableKg(new BigDecimal("20.00"));
        announcement.setTotalKg(new BigDecimal("23.00"));
        announcement.setPricePerKg(new BigDecimal("8.00"));
        announcement.setTimezone("Europe/Paris");
        announcement.setStatus(AnnouncementStatus.COMPLETED);
        entityManager.persist(announcement);
        entityManager.flush();

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(senderId);
        entityManager.persist(bid);
        entityManager.flush();

        return bid.getId();
    }

    // ── Surcharge à bidId (chargeCommissionFromWallet) ──────────────────────

    @Test
    void debitWithBidId_convertedAmount_persistsAndRereadsSourceCurrencyAmountAndRate() {
        UUID userId = openWallet("XOF", new BigDecimal("10000.00"));
        UUID bidId = persistBid();

        walletService.debit(userId, "XOF", new BigDecimal("7869.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, bidId,
                "EUR", new BigDecimal("12.00"), new BigDecimal("655.750000"));

        walletAccountRepository.flush();
        walletTransactionRepository.flush();
        entityManager.clear();

        WalletTransactionEntity reread = walletTransactionRepository
                .findByUserIdAndBidIdAndType(userId, bidId, WalletTransactionType.COMMISSION_DEDUCTED)
                .orElseThrow();

        assertThat(reread.getCurrency()).isEqualTo("XOF");
        assertThat(reread.getAmount()).isEqualByComparingTo(new BigDecimal("-7869.00"));
        assertThat(reread.getSourceCurrency()).isEqualTo("EUR");
        assertThat(reread.getSourceAmount()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(reread.getAppliedRate()).isEqualByComparingTo(new BigDecimal("655.750000"));

        WalletAccountEntity walletAfter = walletAccountRepository
                .findByUserIdAndCurrency(userId, "XOF").orElseThrow();
        assertThat(walletAfter.getBalance()).isEqualByComparingTo(new BigDecimal("2131.00"));
    }

    @Test
    void debitWithBidId_sameCurrencyOverload_leavesSourceColumnsNull() {
        // L'overload à 5 arguments (sans conversion) doit laisser les 3 colonnes NULL —
        // c'est le cas « même devise » de chargeCommissionFromWallet.
        UUID userId = openWallet("EUR", new BigDecimal("50.00"));
        UUID bidId = persistBid();

        walletService.debit(userId, "EUR", new BigDecimal("12.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, bidId);

        walletTransactionRepository.flush();
        entityManager.clear();

        WalletTransactionEntity reread = walletTransactionRepository
                .findByUserIdAndBidIdAndType(userId, bidId, WalletTransactionType.COMMISSION_DEDUCTED)
                .orElseThrow();

        assertThat(reread.getSourceCurrency()).isNull();
        assertThat(reread.getSourceAmount()).isNull();
        assertThat(reread.getAppliedRate()).isNull();
    }

    @Test
    void debitWithBidId_insufficientBalanceAfterConversion_throwsAndPersistsNothing() {
        UUID userId = openWallet("XOF", new BigDecimal("100.00"));
        UUID bidId = persistBid();

        assertThatThrownBy(() -> walletService.debit(userId, "XOF", new BigDecimal("7869.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, bidId,
                "EUR", new BigDecimal("12.00"), new BigDecimal("655.750000")))
                .isInstanceOf(InsufficientWalletBalanceException.class);

        entityManager.clear();

        assertThat(walletTransactionRepository.existsByUserIdAndBidIdAndType(
                userId, bidId, WalletTransactionType.COMMISSION_DEDUCTED)).isFalse();
        WalletAccountEntity walletAfter = walletAccountRepository
                .findByUserIdAndCurrency(userId, "XOF").orElseThrow();
        assertThat(walletAfter.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitWithBidId_rateChangedBetweenTwoDebits_firstPersistedTransactionUnaffected() {
        // Non-régression réelle (base + relecture) de l'immutabilité du taux figé :
        // un deuxième prélèvement, snapshoté avec un taux différent (simulant un
        // changement du taux administré dans exchange_rates entre les deux), ne doit
        // jamais rejaillir sur la transaction déjà persistée par le premier.
        UUID userId = openWallet("XOF", new BigDecimal("20000.00"));
        UUID firstBidId = persistBid();
        UUID secondBidId = persistBid();

        walletService.debit(userId, "XOF", new BigDecimal("7869.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, firstBidId,
                "EUR", new BigDecimal("12.00"), new BigDecimal("655.750000"));
        walletTransactionRepository.flush();
        entityManager.clear();

        // Le taux administré change en base avant le second prélèvement.
        walletService.debit(userId, "XOF", new BigDecimal("7900.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, secondBidId,
                "EUR", new BigDecimal("12.00"), new BigDecimal("658.333333"));
        walletTransactionRepository.flush();
        entityManager.clear();

        WalletTransactionEntity first = walletTransactionRepository
                .findByUserIdAndBidIdAndType(userId, firstBidId, WalletTransactionType.COMMISSION_DEDUCTED)
                .orElseThrow();
        WalletTransactionEntity second = walletTransactionRepository
                .findByUserIdAndBidIdAndType(userId, secondBidId, WalletTransactionType.COMMISSION_DEDUCTED)
                .orElseThrow();

        assertThat(first.getAppliedRate()).isEqualByComparingTo(new BigDecimal("655.750000"));
        assertThat(first.getSourceAmount()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(first.getAmount()).isEqualByComparingTo(new BigDecimal("-7869.00"));

        assertThat(second.getAppliedRate()).isEqualByComparingTo(new BigDecimal("658.333333"));
        assertThat(second.getAmount()).isEqualByComparingTo(new BigDecimal("-7900.00"));
    }

    // ── Surcharge à paymentRef/idempotencyKey (settleNegotiationCommission) ──

    @Test
    void debitWithPaymentRef_convertedAmount_persistsAndRereadsSourceCurrencyAmountAndRate() {
        UUID userId = openWallet("XOF", new BigDecimal("10000.00"));

        walletService.debit(userId, "XOF", new BigDecimal("7869.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "nego_commission_wallet_thread-1",
                "EUR", new BigDecimal("12.00"), new BigDecimal("655.750000"));

        walletTransactionRepository.flush();
        entityManager.clear();

        List<WalletTransactionEntity> rows = walletTransactionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10))
                .getContent();
        assertThat(rows).hasSize(1);
        WalletTransactionEntity reread = rows.get(0);

        assertThat(reread.getPaymentRef()).isEqualTo("thread-1");
        assertThat(reread.getIdempotencyKey()).isEqualTo("nego_commission_wallet_thread-1");
        assertThat(reread.getSourceCurrency()).isEqualTo("EUR");
        assertThat(reread.getSourceAmount()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(reread.getAppliedRate()).isEqualByComparingTo(new BigDecimal("655.750000"));
    }

    @Test
    void debitWithPaymentRef_insufficientBalanceAfterConversion_throwsAndPersistsNothing() {
        UUID userId = openWallet("XOF", new BigDecimal("100.00"));

        assertThatThrownBy(() -> walletService.debit(userId, "XOF", new BigDecimal("7869.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, "thread-2", "nego_commission_wallet_thread-2",
                "EUR", new BigDecimal("12.00"), new BigDecimal("655.750000")))
                .isInstanceOf(InsufficientWalletBalanceException.class);

        entityManager.clear();

        assertThat(walletTransactionRepository.findByIdempotencyKey("nego_commission_wallet_thread-2"))
                .isEmpty();
        WalletAccountEntity walletAfter = walletAccountRepository
                .findByUserIdAndCurrency(userId, "XOF").orElseThrow();
        assertThat(walletAfter.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }
}
