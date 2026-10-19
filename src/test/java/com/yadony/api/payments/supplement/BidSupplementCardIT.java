package com.yadony.api.payments.supplement;

import com.stripe.exception.InvalidRequestException;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.stripe.StripeEventDispatcher;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rail carte du supplément de bout en bout, sur un vrai PostgreSQL (V338, index et contraintes),
 * Stripe simulé : demande → acceptation (PaymentIntent) → webhook {@code payment_intent.succeeded}
 * (rejoué) → versement principal → Transfer du supplément ; variantes : colis refusé après paiement
 * (remboursement) et paiement abouti après le refus de l'expéditeur (paiement tardif remboursé).
 */
@SpringBootTest(properties = {"yadony.bid-supplement.enabled=true", "yadony.bid-supplement.reconcile-enabled=false"})
@ActiveProfiles("e2e")
class BidSupplementCardIT {

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

    @MockitoBean StripeSupplementGateway gateway;

    @Autowired BidSupplementService supplementService;
    @Autowired BidSupplementPaymentFlow paymentFlow;
    @Autowired StripeEventDispatcher dispatcher;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void demandeAcceptationWebhookRejoue_puisVersementApresLePrincipal() throws Exception {
        Parties p = parties();
        UUID supplementId = createSupplement(p);
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        when(gateway.createPaymentIntent(any(), eq("supplement-pi-" + supplementId + "-v1")))
                .thenReturn(new PaymentIntentView(piId, "requires_payment_method", 2110L, "eur", piId + "_secret",
                        null, List.of("card", "paypal")));

        SupplementAcceptResponse accepted = paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid());

        assertThat(accepted.clientSecret()).isEqualTo(piId + "_secret");
        assertThat(accepted.paymentMethodTypes()).containsExactly("card", "paypal");
        assertThat(accepted.supplement().status()).isEqualTo("PAYMENT_PENDING");
        assertThat(accepted.supplement().netAmount()).isNull();
        assertThat(column(supplementId, "stripe_payment_intent_id")).isEqualTo(piId);

        // Second tap : la même PaymentIntent, encore payable, est réutilisée.
        when(gateway.retrievePaymentIntent(piId)).thenReturn(new PaymentIntentView(piId, "requires_payment_method",
                2110L, "eur", piId + "_secret", null, List.of("card", "paypal")));
        assertThat(paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid()).clientSecret())
                .isEqualTo(piId + "_secret");
        verify(gateway, times(1)).createPaymentIntent(any(), anyString());

        String succeeded = event("evt_" + UUID.randomUUID(), "payment_intent.succeeded",
                "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\","
                        + "\"latest_charge\":\"ch_supp\",\"metadata\":{\"bid_supplement_id\":\"" + supplementId
                        + "\",\"scope\":\"BID_SUPPLEMENT\"}}");
        assertThat(dispatcher.dispatch(succeeded)).isTrue();
        assertThat(dispatcher.dispatch(succeeded)).as("rejeu").isTrue();

        assertThat(column(supplementId, "status")).isEqualTo("PAID");
        assertThat(column(supplementId, "stripe_charge_id")).isEqualTo("ch_supp");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, supplementId, "BID_SUPPLEMENT_PAID")).as("un seul paiement enregistré").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE bid_id = ?", Integer.class, p.bidId))
                .as("jamais de ligne payments pour un supplément").isZero();

        when(gateway.findTransferByGroup("supplement_" + supplementId)).thenReturn(Optional.empty());
        when(gateway.createTransfer(eq(2000L), eq("eur"), eq(p.traveler.getStripeAccountId()), eq("ch_supp"),
                eq("supplement_" + supplementId), anyMap(), eq("supplement-transfer-" + supplementId)))
                .thenReturn("tr_supp");
        jdbc.update("UPDATE bids SET status = 'COMPLETED' WHERE id = ?", p.bidId);
        PaymentReleasedEvent released = PaymentReleasedEvent.card(p.bidId, p.traveler.getId(), p.sender.getId(),
                new BigDecimal("50.00"), new BigDecimal("5.00"), "EUR");
        tx.executeWithoutResult(status -> publisher.publishEvent(released));

        awaitStatus(supplementId, "PAID_OUT");
        assertThat(column(supplementId, "stripe_transfer_id")).isEqualTo("tr_supp");

        tx.executeWithoutResult(status -> publisher.publishEvent(released));
        Thread.sleep(500);
        verify(gateway, times(1)).createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString());
    }

    @Test
    void colisRefuseApresPaiement_supplementRembourse() throws Exception {
        Parties p = parties();
        UUID supplementId = createSupplement(p);
        String piId = acceptAndPay(p, supplementId);
        when(gateway.findRefund(piId, supplementId.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), eq("supplement-refund-" + supplementId))).thenReturn("re_supp");

        jdbc.update("UPDATE bids SET status = 'PARCEL_REFUSED' WHERE id = ?", p.bidId);
        assertThat(supplementService.onBidEnded(p.bidId, "PARCEL_REFUSED")).isTrue();

        awaitStatus(supplementId, "REFUNDED");
        assertThat(column(supplementId, "stripe_refund_id")).isEqualTo("re_supp");
        assertThat(column(supplementId, "closed_reason")).isEqualTo("PARCEL_REFUSED");
    }

    @Test
    void refusPendantLePaiement_paymentIntentAnnulee_etPaiementTardifRembourse() throws Exception {
        Parties p = parties();
        UUID supplementId = createSupplement(p);
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(new PaymentIntentView(piId,
                "requires_payment_method", 2110L, "eur", piId + "_secret", null, List.of("card")));
        paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid());
        // L'expéditeur a validé la PaymentSheet juste avant de refuser : l'annulation est refusée.
        when(gateway.cancelPaymentIntent(piId))
                .thenThrow(new InvalidRequestException("already succeeded", null, "req", null, 400, null));
        when(gateway.retrievePaymentIntent(piId)).thenReturn(new PaymentIntentView(piId, "succeeded", 2110L, "eur",
                null, "ch_late", List.of("card")));
        when(gateway.findRefund(piId, supplementId.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), eq("supplement-refund-" + supplementId))).thenReturn("re_late");

        supplementService.decline(p.bidId, supplementId, p.sender.getFirebaseUid());

        awaitStatus(supplementId, "REFUNDED");
        assertThat(column(supplementId, "stripe_charge_id")).isEqualTo("ch_late");
        assertThat(column(supplementId, "stripe_refund_id")).isEqualTo("re_late");
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID bidId) {
    }

    private Parties parties() {
        UserEntity traveler = persistUser("acct_" + UUID.randomUUID(), null);
        UserEntity sender = persistUser(null, "cus_" + UUID.randomUUID());
        UUID bidId = persistAcceptedBid(persistAnnouncement(traveler.getId()), sender.getId());
        return new Parties(traveler, sender, bidId);
    }

    private UUID createSupplement(Parties p) {
        return supplementService.create(p.bidId, p.traveler.getFirebaseUid(), new CreateSupplementRequest(
                new BigDecimal("20"), BidSupplementReason.GRID_ITEM, null, "Téléphone",
                "tracking/" + p.bidId + "/1_supplement.jpg")).id();
    }

    private String acceptAndPay(Parties p, UUID supplementId) throws Exception {
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        when(gateway.createPaymentIntent(any(), eq("supplement-pi-" + supplementId + "-v1")))
                .thenReturn(new PaymentIntentView(piId, "requires_payment_method", 2110L, "eur", piId + "_secret",
                        null, List.of("card")));
        paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid());
        assertThat(dispatcher.dispatch(event("evt_" + UUID.randomUUID(), "payment_intent.succeeded",
                "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\","
                        + "\"latest_charge\":\"ch_" + piId + "\",\"metadata\":{\"bid_supplement_id\":\""
                        + supplementId + "\"}}"))).isTrue();
        assertThat(column(supplementId, "status")).isEqualTo("PAID");
        return piId;
    }

    private static String event(String id, String type, String objectJson) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"" + com.stripe.Stripe.API_VERSION
                + "\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson + "}}";
    }

    private String column(UUID supplementId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM bid_supplements WHERE id = ?", String.class,
                supplementId);
    }

    private void awaitStatus(UUID supplementId, String expected) throws InterruptedException {
        Supplier<String> status = () -> column(supplementId, "status");
        for (int i = 0; i < 100 && !expected.equals(status.get()); i++) {
            Thread.sleep(100);
        }
        assertThat(status.get()).isEqualTo(expected);
    }

    private UserEntity persistUser(String stripeAccountId, String stripeCustomerId) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("supplement-card-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(stripeAccountId == null ? StripeAccountStatus.NOT_CREATED
                : StripeAccountStatus.ONBOARDING_COMPLETE);
        user.setStripeAccountId(stripeAccountId);
        user.setStripeCustomerId(stripeCustomerId);
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

    private UUID persistAcceptedBid(UUID announcementId, UUID senderId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.STRIPE);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("2"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
