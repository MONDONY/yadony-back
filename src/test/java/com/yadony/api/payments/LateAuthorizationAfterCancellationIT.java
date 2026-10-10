package com.yadony.api.payments;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fenêtre de course 3DS de l'annulation avant paiement : une autorisation carte qui arrive APRÈS
 * l'annulation ne promeut jamais le bid, ne met jamais le paiement en séquestre, et l'autorisation
 * est libérée.
 *
 * <p>Annulation avant paiement de bout en bout : vraie base PostgreSQL (migrations Flyway), vrais
 * commits (les écouteurs de notification sont {@code AFTER_COMMIT}), Stripe simulé au niveau de
 * {@link StripeGateway}.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class LateAuthorizationAfterCancellationIT {

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
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentService paymentService;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private StripeGateway stripeGateway;

    @Test
    void lateRequiresCaptureWebhook_afterCancellation_bidStaysCancelled_authorizationReleased() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID announcementId = persistAnnouncement(traveler.getId(), "20.00");
        String piId = "pi_" + UUID.randomUUID();
        UUID bidId = persistBid(announcementId, sender.getId(), PaymentMethod.STRIPE, piId, false);
        UUID paymentId = persistPayment(bidId, PaymentRail.STRIPE, piId);

        // Au clic : 3DS en cours, l'annulation libère le PaymentIntent. Puis Stripe signale une
        // autorisation (le 3DS a abouti au même instant) : relu, il est requires_capture.
        PaymentIntent atCancel = mock(PaymentIntent.class);
        when(atCancel.getId()).thenReturn(piId);
        when(atCancel.getStatus()).thenReturn("requires_action");
        PaymentIntent authorized = mock(PaymentIntent.class);
        when(authorized.getId()).thenReturn(piId);
        when(authorized.getStatus()).thenReturn("requires_capture");
        when(authorized.getAmountCapturable()).thenReturn(4000L);
        when(authorized.getMetadata()).thenReturn(Map.of());
        when(stripeGateway.retrievePaymentIntent(piId)).thenReturn(atCancel, authorized);

        mockMvc.perform(post("/bids/{id}/cancel-before-payment", bidId).with(authentication(as(sender))))
                .andExpect(status().isOk());
        verify(atCancel).cancel(any(PaymentIntentCancelParams.class));

        // Webhook amount_capturable_updated tardif.
        tx.executeWithoutResult(s -> paymentService.applyPaymentEscrowActive(authorized));

        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELLED");
        verify(authorized).cancel(any(PaymentIntentCancelParams.class));
        assertThat(auditCount(paymentId, "LATE_AUTHORIZATION_RELEASED")).isEqualTo(1);
        assertThat(auditCount(paymentId, "PAYMENT_ESCROW_ACTIVE")).isZero();
        assertThat(auditCount(bidId, "BID_CREATED")).isZero();
        assertThat(jdbc.queryForObject("SELECT escrow_released_at IS NULL AND captured_at IS NULL FROM payments "
                + "WHERE id = ?", Boolean.class, paymentId)).isTrue();

        // Le job d'auto-réparation ne relit que les PENDING : jamais ce paiement annulé.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(paymentRepository.findPendingCardPaymentIds(now.plusDays(1), now.minusDays(7),
                org.springframework.data.domain.PageRequest.of(0, 500))).doesNotContain(paymentId);
    }

    /** L'UPDATE conditionnel attend le verrou de l'annulation puis relit la ligne validée. */
    @Test
    void promotionWaitingOnTheCancellationLock_neverOverwritesTheCancellation() throws Exception {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID bidId = persistBid(persistAnnouncement(traveler.getId(), "20.00"), sender.getId(),
                PaymentMethod.STRIPE, "pi_" + UUID.randomUUID(), false);
        UUID paymentId = persistPayment(bidId, PaymentRail.STRIPE, "pi_lock_" + UUID.randomUUID());

        CountDownLatch locked = new CountDownLatch(1);
        Thread cancellation = new Thread(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM payments WHERE id = ? FOR UPDATE", UUID.class, paymentId);
            jdbc.queryForObject("SELECT id FROM bids WHERE id = ? FOR UPDATE", UUID.class, bidId);
            jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = ?", paymentId);
            jdbc.update("UPDATE bids SET status = 'CANCELLED' WHERE id = ?", bidId);
            locked.countDown();
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        cancellation.start();
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicInteger escrow = new AtomicInteger(-1);
        AtomicInteger promoted = new AtomicInteger(-1);
        long start = System.nanoTime();
        tx.executeWithoutResult(s -> {
            escrow.set(paymentRepository.markCardEscrowIfPending(paymentId));
            promoted.set(bidRepository.promoteToEscrowedIfAwaitingPayment(bidId));
        });
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        cancellation.join();

        assertThat(waitedMs).isGreaterThanOrEqualTo(300);
        assertThat(escrow.get()).isZero();
        assertThat(promoted.get()).isZero();
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELLED");
        assertThat(bidRepository.findById(bidId).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
    }

    // --- helpers ---

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
