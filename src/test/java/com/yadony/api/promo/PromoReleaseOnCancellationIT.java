package com.yadony.api.promo;

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
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code promo rendu à l'annulation, contre une vraie base PostgreSQL (verrou pessimiste,
 * colonne {@code timestamptz}, requête native sur {@code bids}) et avec de VRAIS commits :
 * {@link PromoReleaseListener} est un écouteur {@code AFTER_COMMIT}, qu'un test
 * {@code @Transactional} (rollback) ne déclencherait jamais.
 *
 * <p>Scénario du propriétaire : {@code per_user_limit = 1}, colis espèces accepté (commission
 * débitée au taux promo, code racheté), puis annulé par l'expéditeur (commission recréditée) :
 * le même expéditeur peut réutiliser le code sur un nouveau colis.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class PromoReleaseOnCancellationIT {

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
    @Autowired private BidService bidService;
    @Autowired private BidRepository bidRepository;
    @Autowired private PromoService promoService;
    @Autowired private CommissionRateResolver commissionRateResolver;
    @Autowired private PromoCodeRepository promoCodeRepository;
    @Autowired private PromoRedemptionRepository redemptionRepository;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;
    @Autowired private ApplicationEventPublisher publisher;

    private UserEntity sender;
    private UUID promoId;
    private String code;

    @BeforeEach
    void seed() {
        sender = persistUser();
        code = "RELIT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
    void cashBidCancelledBySender_givesCodeBack_andSameSenderReusesIt() {
        UUID firstTraveler = persistUser().getId();
        UUID secondTraveler = persistUser().getId();
        openWallet(firstTraveler, new BigDecimal("100"));
        openWallet(secondTraveler, new BigDecimal("100"));
        UUID firstBid = persistBid(persistAnnouncement(firstTraveler));
        UUID secondBid = persistBid(persistAnnouncement(secondTraveler));
        BigDecimal fullRate = commissionRateResolver.resolve(firstTraveler, sender.getId());
        BigDecimal promoRate = fullRate.subtract(PROMO_RATE).max(BigDecimal.ZERO);

        AcceptBidResponse first = cashCommissionService.acceptCashBid(
                firstBid, firstTraveler, CommissionSource.WALLET_FIRST);
        assertThat(first.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        assertThat(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(promoId, sender.getId()))
                .isEqualTo(1);

        // Annulation par l'expéditeur avant remise : commission recréditée, code rendu (AFTER_COMMIT).
        bidService.cancelBid(firstBid, sender.getFirebaseUid());

        BidEntity cancelled = bidRepository.findById(firstBid).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(cancelled.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
        PromoRedemptionEntity released = redemptionRepository.findByPromoCodeIdAndBidId(promoId, firstBid).orElseThrow();
        assertThat(released.getReleasedAt()).isNotNull();
        assertThat(released.getReleaseReason()).isEqualTo(PromoReleaseListener.REASON_BID_CANCELLED);
        assertThat(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(promoId, sender.getId()))
                .isZero();
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isZero();

        // Rejeu (événement dupliqué) : aucune seconde libération, compteur au plancher.
        assertThat(promoService.releaseForBid(firstBid, PromoReleaseListener.REASON_BID_CANCELLED)).isZero();
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isZero();

        // Le même expéditeur réutilise le code (per_user_limit = 1) sur un nouveau colis.
        AcceptBidResponse second = cashCommissionService.acceptCashBid(
                secondBid, secondTraveler, CommissionSource.WALLET_FIRST);
        assertThat(second.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        BidEntity secondAfter = bidRepository.findById(secondBid).orElseThrow();
        assertThat(secondAfter.getCommissionRate()).isEqualByComparingTo(promoRate);
        assertThat(secondAfter.getPromoCodeId()).isEqualTo(promoId);
        assertThat(redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(promoId, secondBid)).isTrue();
        assertThat(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(promoId, sender.getId()))
                .isEqualTo(1);
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isEqualTo(1);
    }

    @Test
    void releasedRow_isReactivated_whenSameBidRedeemsAgain() {
        UUID bidId = UUID.randomUUID();
        promoService.redeem(code, sender.getId(), bidId, PROMO_RATE);
        assertThat(promoService.releaseForBid(bidId, "TEST")).isEqualTo(1);

        PromoRedemptionEntity again = promoService.redeem(code, sender.getId(), bidId, PROMO_RATE);

        assertThat(again.isReleased()).isFalse();
        assertThat(redemptionRepository.findAll().stream().filter(r -> r.getBidId().equals(bidId))).hasSize(1);
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isEqualTo(1);
    }

    /**
     * Décisions du propriétaire : no-show de l'expéditeur confirmé, colis refusé (même en
     * espèces), remboursement admin, paiement carte abandonné. Chaque événement est publié
     * DEUX fois, dans de vraies transactions : une seule libération, compteur décrémenté une fois.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "SENDER_NO_SHOW", "PARCEL_REFUSED", "ADMIN_REFUND", "PAYMENT_ABANDONED"})
    void ownerDecidedCases_releaseExactlyOnce(String reason) {
        UUID traveler = persistUser().getId();
        UUID bidId = persistBid(persistAnnouncement(traveler));
        promoService.redeem(code, sender.getId(), bidId, PROMO_RATE);
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isEqualTo(1);

        Object event = switch (reason) {
            case "SENDER_NO_SHOW" -> new com.yadony.api.cancellation.events.CancellationConfirmedEvent(
                    bidId, UUID.randomUUID(), com.yadony.api.cancellation.CancellationReason.SENDER_NO_SHOW);
            case "PARCEL_REFUSED" -> new com.yadony.api.matching.events.ParcelRefusedEvent(
                    bidId, traveler, sender.getId(), "contenu interdit");
            case "ADMIN_REFUND" -> new com.yadony.api.payments.events.AdminPaymentRefundedEvent(
                    UUID.randomUUID(), bidId, null, null);
            default -> new com.yadony.api.matching.events.BidAwaitingPaymentAbandonedEvent(bidId, sender.getId());
        };
        tx.executeWithoutResult(status -> publisher.publishEvent(event));
        tx.executeWithoutResult(status -> publisher.publishEvent(event));

        PromoRedemptionEntity released = redemptionRepository.findByPromoCodeIdAndBidId(promoId, bidId).orElseThrow();
        assertThat(released.getReleasedAt()).isNotNull();
        assertThat(released.getReleaseReason()).isEqualTo(reason);
        assertThat(redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(promoId, sender.getId()))
                .isZero();
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isZero();
    }

    /**
     * Bid matérialisé depuis un fil de négociation (rachat porté par le bid,
     * {@code ThreadAcceptedBidListener}) : depuis #418 son paiement est remboursé via le fil sur
     * les mêmes événements, le code est donc rendu comme pour un bid classique — y compris sur
     * un remboursement admin du paiement du FIL (bidId nul dans l'événement).
     */
    @Test
    void negotiatedBid_releasedOnCancellation_andOnAdminRefundOfItsThread() {
        UUID traveler = persistUser().getId();
        UUID announcementId = persistAnnouncement(traveler);
        UUID threadA = UUID.randomUUID();
        UUID threadB = UUID.randomUUID();
        UUID cancelledBid = persistNegotiatedBid(announcementId, threadA);
        UUID refundedBid = persistNegotiatedBid(announcementId, threadB);
        promoService.recordGrantedRedemption(code, sender.getId(), cancelledBid, PROMO_RATE);
        // Code à usage unique par utilisateur : le 2e rachat est un dépassement journalisé.
        promoService.recordGrantedRedemption(code, sender.getId(), refundedBid, PROMO_RATE);

        tx.executeWithoutResult(status -> publisher.publishEvent(
                new com.yadony.api.matching.events.BidRejectedEvent(cancelledBid, sender.getId(), "CANCELLED_BY_SENDER")));
        tx.executeWithoutResult(status -> publisher.publishEvent(
                new com.yadony.api.payments.events.AdminPaymentRefundedEvent(UUID.randomUUID(), null, threadB, null)));

        assertThat(redemptionRepository.findByPromoCodeIdAndBidId(promoId, cancelledBid).orElseThrow().getReleaseReason())
                .isEqualTo(PromoReleaseListener.REASON_BID_CANCELLED);
        assertThat(redemptionRepository.findByPromoCodeIdAndBidId(promoId, refundedBid).orElseThrow().getReleaseReason())
                .isEqualTo(PromoReleaseListener.REASON_ADMIN_REFUND);
        assertThat(promoCodeRepository.findById(promoId).orElseThrow().getRedeemedCount()).isZero();
    }

    @Test
    void nativeBidQueries_readTheBidsTable() {
        UUID traveler = persistUser().getId();
        UUID announcementId = persistAnnouncement(traveler);
        UUID bidId = persistBid(announcementId);
        UUID threadId = UUID.randomUUID();
        UUID negotiated = persistNegotiatedBid(announcementId, threadId);

        assertThat(redemptionRepository.findBidPaymentMethod(bidId)).contains("CASH");
        assertThat(redemptionRepository.findBidPaymentMethod(UUID.randomUUID())).isEmpty();
        assertThat(redemptionRepository.findBidIdsByNegotiationThreadId(threadId)).containsExactly(negotiated);
        assertThat(redemptionRepository.findBidIdsByNegotiationThreadId(UUID.randomUUID())).isEmpty();
    }

    // --- helpers (données commitées : l'écouteur AFTER_COMMIT doit les voir) ---

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("promo-release-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private void openWallet(UUID userId, BigDecimal balance) {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(balance);
        walletAccountRepository.saveAndFlush(wallet);
    }

    private UUID persistAnnouncement(UUID travelerId) {
        return tx.execute(status -> {
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
            return announcement.getId();
        });
    }

    /**
     * Bid matérialisé depuis un fil : la clé étrangère vers {@code negotiation_threads} est
     * neutralisée le temps de l'insertion (un fil réel exige toute une demande de colis), seul
     * {@code linked_negotiation_thread_id} compte ici.
     */
    private UUID persistNegotiatedBid(UUID announcementId, UUID threadId) {
        return tx.execute(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(sender.getId());
            bid.setPaymentMethod(PaymentMethod.STRIPE);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("5"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setPromoCode(code);
            bid.setLinkedNegotiationThreadId(threadId);
            entityManager.persist(bid);
            entityManager.flush();
            return bid.getId();
        });
    }

    private UUID persistBid(UUID announcementId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(sender.getId());
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("5"));
            bid.setStatus(BidStatus.PENDING);
            bid.setPromoCode(code);
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
