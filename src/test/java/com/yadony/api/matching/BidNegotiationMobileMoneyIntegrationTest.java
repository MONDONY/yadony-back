package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Accord d'une négociation de trajet en mobile money, avec le VRAI
 * {@code MobileMoneyBidPaymentService#acceptBid} appelé dans la transaction de
 * {@link BidNegotiationService#accept} : le test unitaire ne peut pas prouver que acceptBid
 * relit bien le bid en PENDING (verrou + flush dans la même transaction), ni que la capacité
 * est réellement prélevée sur l'annonce.
 *
 * <p>Vraie base PostgreSQL (décor de {@code WalletMobileMoneyTopupIT}) : acceptBid verrouille
 * le bid en {@code FOR NO KEY UPDATE}, refusé par H2. Classe dédiée : {@code
 * yadony.pawapay.enabled=true} change le contexte Spring, inutile de l'imposer aux autres tests
 * du cycle de négociation.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Fil de négociation — accord payé en mobile money")
class BidNegotiationMobileMoneyIntegrationTest {

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
        // Rail fermé par défaut : acceptBid répondrait 422 « mobile-money-disabled ».
        registry.add("yadony.pawapay.enabled", () -> true);
        registry.add("yadony.pawapay.poll-cron", () -> "-");
        registry.add("yadony.pawapay.balance-cron", () -> "-");
        registry.add("yadony.pawapay.deadline-cron", () -> "-");
    }

    @Autowired private BidNegotiationService negotiationService;
    @Autowired private BidRepository bidRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BidNegotiationMessageRepository messageRepository;
    @Autowired private PaymentRepository paymentRepository;

    @BeforeEach
    void cleanDb() {
        paymentRepository.deleteAll();
        messageRepository.deleteAll();
        bidRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("accord → AWAITING_PAYMENT, paiement pawaPay en attente au prix négocié, capacité réservée")
    void agreement_reservesCapacityAndCreatesPawapayPayment() {
        UserEntity sender = persistUser("uid-mm-sender-" + UUID.randomUUID(), false);
        UserEntity traveler = persistUser("uid-mm-traveler-" + UUID.randomUUID(), true);
        AnnouncementEntity announcement = persistXofAnnouncement(traveler.getId());
        BidEntity thread = persistMobileMoneyThread(announcement.getId(), sender.getId());
        persistProposal(thread.getId(), sender.getId());

        negotiationService.accept(thread.getId(), traveler.getFirebaseUid());

        BidEntity after = bidRepository.findById(thread.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        assertThat(after.getAwaitingPaymentExpiresAt()).isNotNull();
        assertThat(after.getNegotiatedGrossEur()).isEqualByComparingTo("20000");

        PaymentEntity payment = paymentRepository.findByBidId(thread.getId()).orElseThrow();
        assertThat(payment.getRail()).isEqualTo(PaymentRail.PAWAPAY);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAmount()).isEqualByComparingTo("20000");
        assertThat(payment.getCurrency()).isEqualTo("XOF");

        assertThat(announcementRepository.findById(announcement.getId()).orElseThrow().getAvailableKg())
                .describedAs("restoreCapacityIfNeeded rendra ces kg si le dépôt n'arrive pas : "
                        + "ils doivent donc avoir été prélevés à l'accord")
                .isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("voyageur sans compte de versement actif → accord refusé, fil intact, rien de réservé")
    void agreementWithoutPayoutAccount_isRolledBack() {
        UserEntity sender = persistUser("uid-mm2-sender-" + UUID.randomUUID(), false);
        UserEntity traveler = persistUser("uid-mm2-traveler-" + UUID.randomUUID(), false);
        AnnouncementEntity announcement = persistXofAnnouncement(traveler.getId());
        BidEntity thread = persistMobileMoneyThread(announcement.getId(), sender.getId());
        persistProposal(thread.getId(), sender.getId());

        assertThatThrownBy(() -> negotiationService.accept(thread.getId(), traveler.getFirebaseUid()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("mobile-money-account-required"));

        BidEntity after = bidRepository.findById(thread.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.NEGOTIATING);
        assertThat(after.getNegotiatedGrossEur()).isNull();
        assertThat(paymentRepository.findByBidId(thread.getId())).isEmpty();
        assertThat(messageRepository.findByBidIdOrderByCreatedAtAsc(thread.getId())).hasSize(1);
        assertThat(announcementRepository.findById(announcement.getId()).orElseThrow().getAvailableKg())
                .isEqualByComparingTo("10.00");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserEntity persistUser(String firebaseUid, boolean mobileMoneyPayout) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        u.setRoles(roles);
        if (mobileMoneyPayout) {
            u.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
            u.setMobileMoneyMsisdn("221771234567");
            u.setMobileMoneyProvider("ORANGE_SEN");
            u.setMobileMoneyCountry("SN");
            u.setMobileMoneyCurrency("XOF");
        }
        return userRepository.save(u);
    }

    private AnnouncementEntity persistXofAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Dakar");
        a.setArrivalCity("Abidjan");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Dakar Centre");
        a.setPickupLat(new BigDecimal("14.693000"));
        a.setPickupLng(new BigDecimal("-17.447000"));
        a.setDeliveryAddressLabel("Abidjan Plateau");
        a.setDeliveryLat(new BigDecimal("5.320000"));
        a.setDeliveryLng(new BigDecimal("-4.020000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5000"));
        a.setCurrency("XOF");
        a.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.MOBILE_MONEY, PaymentMethod.CASH));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setNegotiable(true);
        return announcementRepository.save(a);
    }

    private BidEntity persistMobileMoneyThread(UUID announcementId, UUID senderId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setStatus(BidStatus.NEGOTIATING);
        bid.setNegotiationRound(1);
        bid.setCommissionRate(new BigDecimal("0.05"));
        bid.setCurrency("XOF");
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        return bidRepository.save(bid);
    }

    private void persistProposal(UUID bidId, UUID authorId) {
        messageRepository.save(BidNegotiationMessageEntity.create(
                bidId, authorId, BidNegotiationMessageKind.PROPOSAL,
                new BigDecimal("20000"), null));
    }
}
