package com.yadony.api.payments.cash;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.promo.PromoCodeEntity;
import com.yadony.api.promo.PromoCodeRepository;
import com.yadony.api.promo.PromoCodeStatus;
import com.yadony.api.promo.PromoCodeTarget;
import com.yadony.api.promo.PromoRedemptionRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code promo d'un bid ESPÈCES contre une vraie base PostgreSQL : avant ce correctif, le taux
 * réduit était appliqué à la commission sans jamais écrire dans {@code promo_redemptions}, si
 * bien que {@code per_user_limit} et {@code max_redemptions} (qui comptent ces lignes) ne
 * s'appliquaient jamais — un même expéditeur obtenait la remise sur chacun de ses colis.
 *
 * <p>Même décor que {@link CashCommissionWalletSplitIT} ({@code EmbeddedPostgres}, profil
 * {@code e2e}) : {@code @Lock(PESSIMISTIC_WRITE)} sur {@code promo_codes} n'existe pas en H2.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class CashCommissionPromoRedemptionIT {

    private static final BigDecimal PROMO_RATE = new BigDecimal("0.010");

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
    @Autowired private CommissionRateResolver commissionRateResolver;
    @Autowired private PromoCodeRepository promoCodeRepository;
    @Autowired private PromoRedemptionRepository redemptionRepository;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private UUID senderId;
    private UUID promoId;
    private String code;

    @BeforeEach
    void seed() {
        senderId = persistUser();
        code = "CASHIT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        PromoCodeEntity promo = new PromoCodeEntity();
        promo.setCode(code);
        promo.setRate(PROMO_RATE);
        promo.setTarget(PromoCodeTarget.SENDER);
        promo.setStatus(PromoCodeStatus.ACTIVE);
        promo.setPerUserLimit(1);
        promo.setMaxRedemptions(100);
        promoId = promoCodeRepository.saveAndFlush(promo).getId();
    }

    @Test
    void secondCashBidOfSameSender_withPerUserLimitOne_isAcceptedAtFullRate_singleRedemption() {
        UUID firstTraveler = persistUser();
        UUID secondTraveler = persistUser();
        openWallet(firstTraveler, new BigDecimal("100"));
        openWallet(secondTraveler, new BigDecimal("100"));
        UUID firstBid = persistBid(persistAnnouncement(firstTraveler));
        UUID secondBid = persistBid(persistAnnouncement(secondTraveler));
        BigDecimal fullRate = commissionRateResolver.resolve(firstTraveler, senderId);

        AcceptBidResponse first = cashCommissionService.acceptCashBid(
                firstBid, firstTraveler, CommissionSource.WALLET_FIRST);
        entityManager.flush();
        AcceptBidResponse second = cashCommissionService.acceptCashBid(
                secondBid, secondTraveler, CommissionSource.WALLET_FIRST);
        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        assertThat(second.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);

        BidEntity firstAfter = entityManager.find(BidEntity.class, firstBid);
        BidEntity secondAfter = entityManager.find(BidEntity.class, secondBid);
        // 1er colis : remise appliquée ET enregistrée.
        assertThat(firstAfter.getCommissionRate())
                .isEqualByComparingTo(fullRate.subtract(PROMO_RATE).max(BigDecimal.ZERO));
        assertThat(firstAfter.getPromoCodeId()).isEqualTo(promoId);
        assertThat(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(promoId, firstBid)).isTrue();
        // 2e colis : limite par utilisateur atteinte → repli au taux plein, aucun rachat.
        assertThat(secondAfter.getCommissionRate()).isEqualByComparingTo(fullRate);
        assertThat(secondAfter.getPromoCodeId()).isNull();
        assertThat(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(promoId, secondBid)).isFalse();

        assertThat(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(promoId, senderId)).isEqualTo(1);
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isEqualTo(1);
    }

    @Test
    void replayedAcceptance_ofSameBid_redeemsOnlyOnce() {
        UUID traveler = persistUser();
        openWallet(traveler, new BigDecimal("100"));
        UUID bidId = persistBid(persistAnnouncement(traveler));

        cashCommissionService.acceptCashBid(bidId, traveler, CommissionSource.WALLET_FIRST);
        entityManager.flush();
        AcceptBidResponse replay = cashCommissionService.acceptCashBid(bidId, traveler, CommissionSource.WALLET_FIRST);
        entityManager.flush();
        entityManager.clear();

        assertThat(replay.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        assertThat(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(promoId, senderId)).isEqualTo(1);
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isEqualTo(1);
    }

    // --- helpers ---

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("promo-cash-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }

    private void openWallet(UUID userId, BigDecimal balance) {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(balance);
        walletAccountRepository.saveAndFlush(wallet);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId) {
        AnnouncementEntity announcement = new AnnouncementEntity();
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
        announcement.setPricePerKg(new BigDecimal("20.00"));
        announcement.setCurrency("EUR");
        announcement.setTimezone("Europe/Paris");
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        entityManager.persist(announcement);
        return announcement;
    }

    private UUID persistBid(AnnouncementEntity announcement) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(senderId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCurrency("EUR");
        bid.setWeightKg(new BigDecimal("5"));
        bid.setStatus(BidStatus.PENDING);
        bid.setPromoCode(code);
        entityManager.persist(bid);
        entityManager.flush();
        return bid.getId();
    }
}
