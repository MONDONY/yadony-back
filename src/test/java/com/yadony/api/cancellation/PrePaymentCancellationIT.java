package com.yadony.api.cancellation;

import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCancelParams;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.StripeGateway;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Annulation avant paiement de bout en bout : vraie base PostgreSQL (migrations Flyway), vrais
 * commits (les écouteurs de notification sont {@code AFTER_COMMIT}), Stripe simulé au niveau de
 * {@link StripeGateway}.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class PrePaymentCancellationIT {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private BidRepository bidRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private StripeGateway stripeGateway;

    @Test
    void negotiatedCardRequest_cancelled_authorizationReleased_travelerNotified_idempotent() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID announcementId = persistAnnouncement(traveler.getId(), "20.00");
        String piId = "pi_" + UUID.randomUUID();
        UUID bidId = persistBid(announcementId, sender.getId(), PaymentMethod.STRIPE, piId, true);
        UUID paymentId = persistPayment(bidId, PaymentRail.STRIPE, piId);
        PaymentIntent pi = intent(piId, "requires_payment_method");

        mockMvc.perform(post("/bids/{id}/cancel-before-payment", bidId).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.alreadyCancelled").value(false));

        verify(pi).cancel(any(PaymentIntentCancelParams.class));
        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getRejectionReason()).isEqualTo(PrePaymentCancellationService.REASON);
        assertThat(bid.isDeletedBySender()).isFalse();
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELLED");
        assertThat(auditCount(bidId, "BID_CANCELLED_BEFORE_PAYMENT")).isEqualTo(1);
        assertThat(auditCount(paymentId, "PAYMENT_CANCELLED_BEFORE_PAYMENT")).isEqualTo(1);
        assertThat(availableKg(announcementId)).isEqualByComparingTo("20.00");
        assertThat(awaitNotification(traveler.getId())).isEqualTo(1);
        assertThat(notificationCount(sender.getId())).isZero();

        // Double appel : rien n'est refait.
        mockMvc.perform(post("/bids/{id}/cancel-before-payment", bidId).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyCancelled").value(true));
        assertThat(auditCount(bidId, "BID_CANCELLED_BEFORE_PAYMENT")).isEqualTo(1);
        Thread.sleep(300);
        assertThat(notificationCount(traveler.getId())).isEqualTo(1);
    }

    @Test
    void mobileMoneyRequest_cancelled_kilosGivenBack() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID announcementId = persistAnnouncement(traveler.getId(), "15.00");
        UUID bidId = persistBid(announcementId, sender.getId(), PaymentMethod.MOBILE_MONEY, null, false);
        UUID paymentId = persistPayment(bidId, PaymentRail.PAWAPAY, null);

        mockMvc.perform(post("/bids/{id}/cancel-before-payment", bidId).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(availableKg(announcementId)).isEqualByComparingTo("20.00");
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELLED");
        assertThat(awaitNotification(traveler.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND type = "
                + "'BID_CANCELLED_BEFORE_PAYMENT'", String.class, traveler.getId())).contains("kilos réservés");
        verify(stripeGateway, never()).retrievePaymentIntent(any());
    }

    @Test
    void cardAuthorizedMeanwhile_conflict_nothingTouched() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID announcementId = persistAnnouncement(traveler.getId(), "20.00");
        String piId = "pi_" + UUID.randomUUID();
        UUID bidId = persistBid(announcementId, sender.getId(), PaymentMethod.STRIPE, piId, false);
        UUID paymentId = persistPayment(bidId, PaymentRail.STRIPE, piId);
        PaymentIntent pi = intent(piId, "requires_capture");

        mockMvc.perform(post("/bids/{id}/cancel-before-payment", bidId).with(authentication(as(sender))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("payment-already-authorized"));

        verify(pi, never()).cancel(any(PaymentIntentCancelParams.class));
        assertThat(bidRepository.findById(bidId).orElseThrow().getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(auditCount(bidId, "BID_CANCELLED_BEFORE_PAYMENT")).isZero();
    }

    @Test
    void travelerCannotCancelTheSendersRequest() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID bidId = persistBid(persistAnnouncement(traveler.getId(), "20.00"), sender.getId(),
                PaymentMethod.STRIPE, null, false);

        mockMvc.perform(post("/bids/{id}/cancel-before-payment", bidId).with(authentication(as(traveler))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        assertThat(bidRepository.findById(bidId).orElseThrow().getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
    }

    @Test
    void unauthenticated_401() throws Exception {
        mockMvc.perform(post("/bids/{id}/cancel-before-payment", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private PaymentIntent intent(String id, String status) throws Exception {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn(id);
        when(pi.getStatus()).thenReturn(status);
        when(stripeGateway.retrievePaymentIntent(id)).thenReturn(pi);
        return pi;
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"), new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private int auditCount(UUID entityId, String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, entityId, action);
        return n == null ? 0 : n;
    }

    private String paymentStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private BigDecimal availableKg(UUID announcementId) {
        return jdbc.queryForObject("SELECT available_kg FROM announcements WHERE id = ?", BigDecimal.class,
                announcementId);
    }

    private int notificationCount(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = "
                + "'BID_CANCELLED_BEFORE_PAYMENT'", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** Les notifications partent après le commit, sur un fil {@code @Async}. */
    private int awaitNotification(UUID userId) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        int n = notificationCount(userId);
        while (n < 1 && System.currentTimeMillis() < until) {
            Thread.sleep(100);
            n = notificationCount(userId);
        }
        return n;
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("prepayment-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private UUID persistAnnouncement(UUID travelerId, String availableKg) {
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
            announcement.setAvailableKg(new BigDecimal(availableKg));
            announcement.setTotalKg(new BigDecimal("23.00"));
            announcement.setPricePerKg(new BigDecimal("20.00"));
            announcement.setCurrency("EUR");
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.MOBILE_MONEY));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    private UUID persistBid(UUID announcementId, UUID senderId, PaymentMethod method, String piId,
                            boolean negotiated) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(method);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("5"));
            bid.setStatus(BidStatus.AWAITING_PAYMENT);
            bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(2));
            bid.setPaymentIntentId(piId);
            if (negotiated) {
                bid.setNegotiatedGrossEur(new BigDecimal("40.00"));
                bid.setNegotiationRound(1);
            }
            entityManager.persist(bid);
            return bid.getId();
        });
    }

    private UUID persistPayment(UUID bidId, PaymentRail rail, String piId) {
        return tx.execute(status -> {
            PaymentEntity payment = new PaymentEntity();
            payment.setBidId(bidId);
            payment.setRail(rail);
            payment.setStripePaymentIntentId(piId);
            payment.setAmount(new BigDecimal("40.00"));
            payment.setCommissionAmount(new BigDecimal("4.80"));
            payment.setCurrency("EUR");
            payment.setStatus(PaymentStatus.PENDING);
            entityManager.persist(payment);
            return payment.getId();
        });
    }
}
