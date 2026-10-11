package com.yadony.api.payments;

import com.stripe.model.PaymentIntent;
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
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.BidStatus;
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
import org.springframework.mock.web.MockHttpServletResponse;
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
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ordre de verrous unique colis → paiement (YADONY-BACK-STAGING-18, PROD_CHECKLIST 9.25), sur une
 * vraie base PostgreSQL : webhook {@code amount_capturable_updated}, {@code confirm-payment},
 * {@code negotiation/checkout} (auto-réparation) et annulation avant paiement concurrents sur le
 * même paiement ne s'interbloquent jamais, et un seul passage en séquestre promeut le colis une
 * seule fois.
 *
 * <p>Les chemins de paiement sont appelés SANS le rejeu de {@code TransientLockRetry} : un
 * interblocage ferait échouer le test au lieu d'être masqué par le filet.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class PaymentLockOrderIT {

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

    private static final String CANCEL_THREAD = "cancel-before-payment";

    @Autowired private MockMvc mockMvc;
    @Autowired private BidRepository bidRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PaymentService paymentService;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private StripeGateway stripeGateway;

    /**
     * Reproduction de l'incident du 11/10 : {@code negotiation/checkout} tient le colis (FOR UPDATE)
     * puis rejoue {@code confirmBidPayment} ; {@code confirm-payment} arrive pendant ce temps.
     * Avant le correctif, confirm-payment prenait le paiement puis attendait le colis, le checkout
     * attendait le paiement : PostgreSQL tuait confirm-payment (500).
     */
    @Test
    void checkoutHoldingTheBid_versusConfirmPayment_noDeadlock_promotedOnce() throws Exception {
        Fixture f = fixture();
        PaymentIntent authorized = authorized(f.piId);
        when(stripeGateway.retrievePaymentIntent(f.piId)).thenReturn(authorized);

        CountDownLatch bidLocked = new CountDownLatch(1);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicBoolean checkoutSettled = new AtomicBoolean();
        Thread checkout = new Thread(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    bidRepository.findByIdForUpdate(f.bidId).orElseThrow();
                    bidLocked.countDown();
                    sleep(500); // confirm-payment démarre pendant que le colis est tenu
                    checkoutSettled.set(paymentService.confirmBidPayment(f.bidId));
                });
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                bidLocked.countDown();
            }
        });
        checkout.start();
        assertThat(bidLocked.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean confirmed = new AtomicBoolean();
        try {
            confirmed.set(paymentService.confirmBidPayment(f.bidId));
        } catch (Throwable t) {
            errors.add(t);
        }
        checkout.join(15_000);

        assertThat(errors).isEmpty();
        assertThat(checkoutSettled.get()).isTrue();
        assertThat(confirmed.get()).isTrue();
        assertPaidOnce(f);
    }

    /**
     * Webhook, confirm-payment et annulation avant paiement lâchés au même instant, sur plusieurs
     * colis : jamais d'interblocage ; soit le paiement gagne (séquestre et promotion une seule
     * fois, annulation refusée), soit l'annulation gagne (rien n'est promu).
     */
    @Test
    void webhookConfirmPaymentAndCancellation_concurrent_noDeadlock_singleEscrow() throws Exception {
        for (int round = 0; round < 6; round++) {
            Fixture f = fixture();
            PaymentIntent authorized = authorized(f.piId);
            PaymentIntent atCancel = mock(PaymentIntent.class);
            when(atCancel.getId()).thenReturn(f.piId);
            when(atCancel.getStatus()).thenReturn("requires_action");
            // L'annulation voit encore le 3DS en cours, les deux autres l'autorisation.
            when(stripeGateway.retrievePaymentIntent(anyString())).thenAnswer(inv ->
                    Thread.currentThread().getName().startsWith(CANCEL_THREAD) ? atCancel : authorized);

            CyclicBarrier start = new CyclicBarrier(3);
            Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
            AtomicReference<MockHttpServletResponse> cancelResponse = new AtomicReference<>();

            Thread webhook = new Thread(() -> run(start, errors,
                    () -> tx.executeWithoutResult(s -> paymentService.applyPaymentEscrowActive(authorized))),
                    "webhook-" + round);
            Thread confirm = new Thread(() -> run(start, errors,
                    () -> paymentService.confirmBidPayment(f.bidId)), "confirm-" + round);
            Thread cancel = new Thread(() -> run(start, errors, () -> {
                try {
                    cancelResponse.set(mockMvc.perform(post("/bids/{id}/cancel-before-payment", f.bidId)
                            .with(authentication(as(f.sender)))).andReturn().getResponse());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }), CANCEL_THREAD + "-" + round);
            webhook.start();
            confirm.start();
            cancel.start();
            webhook.join(20_000);
            confirm.join(20_000);
            cancel.join(20_000);

            assertThat(errors).as("round %d", round).isEmpty();
            MockHttpServletResponse response = cancelResponse.get();
            assertThat(response).isNotNull();
            // Jamais le filet « verrou refusé » de l'annulation (payment-in-progress).
            assertThat(response.getContentAsString()).doesNotContain("payment-in-progress");

            String bidStatus = jdbc.queryForObject("SELECT status FROM bids WHERE id = ?", String.class, f.bidId);
            if ("CANCELLED".equals(bidStatus)) {
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(paymentStatus(f.paymentId)).isIn("CANCELLED", "REFUNDED");
                assertThat(auditCount(f.paymentId, "PAYMENT_ESCROW_ACTIVE")).isZero();
                assertThat(auditCount(f.bidId, "BID_CREATED")).isZero();
            } else {
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(response.getContentAsString()).contains("payment-already-authorized");
                assertPaidOnce(f);
            }
        }
    }

    // --- helpers ---

    private record Fixture(UserEntity sender, UUID bidId, UUID paymentId, String piId) {
    }

    private Fixture fixture() {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        String piId = "pi_" + UUID.randomUUID();
        UUID bidId = persistBid(persistAnnouncement(traveler.getId()), sender.getId(), piId);
        UUID paymentId = persistPayment(bidId, piId);
        return new Fixture(sender, bidId, paymentId, piId);
    }

    private void assertPaidOnce(Fixture f) {
        assertThat(jdbc.queryForObject("SELECT status FROM bids WHERE id = ?", String.class, f.bidId))
                .isEqualTo(BidStatus.PAYMENT_ESCROWED.name());
        assertThat(paymentStatus(f.paymentId)).isEqualTo("ESCROW");
        assertThat(auditCount(f.paymentId, "PAYMENT_ESCROW_ACTIVE")).isEqualTo(1);
        assertThat(auditCount(f.bidId, "BID_CREATED")).isEqualTo(1);
    }

    private static PaymentIntent authorized(String piId) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn(piId);
        when(pi.getStatus()).thenReturn("requires_capture");
        when(pi.getAmountCapturable()).thenReturn(4000L);
        when(pi.getLatestCharge()).thenReturn("ch_" + UUID.randomUUID());
        when(pi.getMetadata()).thenReturn(Map.of());
        return pi;
    }

    private static void run(CyclicBarrier start, Queue<Throwable> errors, Runnable work) {
        try {
            start.await(5, TimeUnit.SECONDS);
            work.run();
        } catch (Throwable t) {
            errors.add(t);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("lock-order-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
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
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    private UUID persistBid(UUID announcementId, UUID senderId, String piId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.STRIPE);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("5"));
            bid.setStatus(BidStatus.AWAITING_PAYMENT);
            bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(2));
            bid.setPaymentIntentId(piId);
            bid.setNegotiatedGrossEur(new BigDecimal("40.00"));
            bid.setNegotiationRound(1);
            entityManager.persist(bid);
            return bid.getId();
        });
    }

    private UUID persistPayment(UUID bidId, String piId) {
        return tx.execute(status -> {
            PaymentEntity payment = new PaymentEntity();
            payment.setBidId(bidId);
            payment.setRail(PaymentRail.STRIPE);
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
