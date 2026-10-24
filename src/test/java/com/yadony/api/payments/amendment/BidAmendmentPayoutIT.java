package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
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
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.StripeTransferLookup;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.amendment.events.BidAmendmentPaidOutEvent;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.supplement.StripeSupplementGateway.ChargeView;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Versement au voyageur du net d'une modification carte, sur un vrai PostgreSQL, Stripe simulé : la modification suit
 * le versement du principal ({@code PaymentReleasedEvent}, quel que soit son rail), ou la livraison quand le principal
 * est payé en espèces (aucun versement principal n'est alors publié). Transfer Connect adossé à la charge de la
 * modification, groupe {@code amendment_<id>}, jamais confondu avec le versement principal.
 */
@SpringBootTest(properties = "yadony.bid-amendment.enabled=true")
@ActiveProfiles("e2e")
@RecordApplicationEvents
@DisplayName("BidAmendmentPayouts : versement du net d'une modification carte (PostgreSQL)")
class BidAmendmentPayoutIT {

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

    @MockitoBean StripeAmendmentGateway gateway;
    @MockitoBean com.yadony.api.payments.supplement.StripeSupplementGateway supplementGateway;
    @MockitoBean AdminAlertEscalator alerts;

    @Autowired BidAmendmentPaymentFlow flow;
    @Autowired BidAmendmentService service;
    @Autowired BidAmendmentReconciliationJob job;
    @Autowired BidAmendmentRepository amendmentRepository;
    @Autowired StripeEventDispatcher dispatcher;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApplicationEvents events;

    // ── Déclencheurs ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("principal carte versé (PaymentReleasedEvent) : la modification carte acceptée est versée")
    void paymentReleasedCardPrincipal_paysOutCardAmendment() throws Exception {
        Parties p = parties(PaymentMethod.STRIPE);
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_rel");
        stubTransfer("ch_rel", "tr_rel");

        release(p);

        verify(gateway, timeout(5000)).createTransfer(eq(3000L), eq("eur"), eq(p.traveler.getStripeAccountId()),
                eq("ch_rel"), any(), anyMap(), eq("amendment-transfer-" + id));
        awaitColumn(id, "status", "PAID_OUT");
        assertThat(column(id, "stripe_transfer_id")).isEqualTo("tr_rel");
        assertThat(column(id, "paid_out_at")).isNotNull();
        assertThat(auditActions(id)).containsOnlyOnce("BID_AMENDMENT_PAID_OUT");
        assertThat(events.stream(BidAmendmentPaidOutEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.amendmentId()).isEqualTo(id);
            assertThat(e.travelerId()).isEqualTo(p.traveler.getId());
            assertThat(e.netAmount()).isEqualByComparingTo("30.00");
        });
    }

    @Test
    @DisplayName("principal espèces livré (DeliveryConfirmedEvent) : la modification carte est versée sans release")
    void cashPrincipal_deliveryConfirmed_paysOutCardAmendment() throws Exception {
        Parties p = parties(PaymentMethod.CASH);
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_cash");
        stubTransfer("ch_cash", "tr_cash");

        deliver(p);

        verify(gateway, timeout(5000)).createTransfer(eq(3000L), eq("eur"), eq(p.traveler.getStripeAccountId()),
                eq("ch_cash"), any(), anyMap(), eq("amendment-transfer-" + id));
        awaitColumn(id, "status", "PAID_OUT");
    }

    @Test
    @DisplayName("principal carte livré mais pas encore versé : la livraison seule ne verse pas la modification")
    void cardPrincipal_deliveryConfirmed_doesNotPayOutBeforeRelease() throws Exception {
        Parties p = parties(PaymentMethod.STRIPE);
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_wait");
        stubTransfer("ch_wait", "tr_wait");

        deliver(p);

        verify(gateway, after(1500).never()).createTransfer(anyLong(), anyString(), anyString(), anyString(), any(),
                anyMap(), anyString());
        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("voyageur aux versements gelés : la modification reste acceptée, alerte, aucun Transfer")
    void heldPayout_skipped() throws Exception {
        Parties p = parties(PaymentMethod.STRIPE);
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_held");
        stubTransfer("ch_held", "tr_held");
        jdbc.update("INSERT INTO payout_holds (id, user_id, reason, held_since, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?, 'BANNED', now(), now(), now())", p.traveler.getId());

        release(p);

        verify(alerts, timeout(5000)).raiseOnce(eq("AMEND_PAYOUT_BLOCKED_" + id), anyString(), anyMap());
        verify(gateway, never()).createTransfer(anyLong(), anyString(), anyString(), anyString(), any(), anyMap(),
                anyString());
        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("Transfer groupé amendment_<id>, sans payment_id : StripeTransferLookup du principal n'est pas trompé")
    void transferGroupIsAmendmentNotPayment() throws Exception {
        Parties p = parties(PaymentMethod.STRIPE);
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_grp");
        stubTransfer("ch_grp", "tr_grp");

        release(p);

        ArgumentCaptor<String> group = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(gateway, timeout(5000)).createTransfer(anyLong(), anyString(), anyString(), anyString(),
                group.capture(), metadata.capture(), anyString());
        assertThat(group.getValue()).isEqualTo("amendment_" + id)
                .isNotEqualTo(StripeTransferLookup.transferGroup(id))
                .doesNotStartWith("payment_");
        assertThat(metadata.getValue()).containsEntry("bid_amendment_id", id.toString())
                .containsEntry("amendment_bid_id", p.bidId.toString())
                .doesNotContainKey("payment_id");
        verify(gateway).findTransferByGroup("amendment_" + id);
    }

    // ── Remboursements carte ─────────────────────────────────────────────────

    @Test
    @DisplayName("remboursement demandé (principal remboursé par l'admin) : Refund carte de la modification acquise")
    void refundRequested_card_refunds() throws Exception {
        Parties p = parties(PaymentMethod.STRIPE);
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_ref");
        when(gateway.findRefund(piId, id.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), anyString())).thenReturn("re_ref");

        assertThat(service.onPrincipalRefunded(p.bidId)).isTrue();

        verify(gateway, timeout(5000)).createRefund(eq(piId), anyMap(), eq("amendment-refund-" + id));
        awaitColumn(id, "stripe_refund_id", "re_ref");
        assertThat(column(id, "status")).isEqualTo("REFUND_PENDING");
    }

    @Test
    @DisplayName("remboursement demandé d'une modification déjà versée : alerte AMEND_REFUND_PAID_OUT_, aucun Refund")
    void refundRequested_paidOut_alertsWithoutRefund() throws Exception {
        Parties p = parties(PaymentMethod.STRIPE);
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_po");
        stubTransfer("ch_po", "tr_po");
        release(p);
        awaitColumn(id, "status", "PAID_OUT");

        service.onPrincipalRefunded(p.bidId);

        verify(alerts, timeout(5000)).raiseOnce(eq("AMEND_REFUND_PAID_OUT_" + id), anyString(), anyMap());
        verify(gateway, never()).createRefund(anyString(), anyMap(), anyString());
        assertThat(column(id, "status")).isEqualTo("PAID_OUT");
    }

    // ── Filet ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("filet : versement manqué d'un colis livré, principal versé ou espèces, au-delà de 30 min seulement")
    void job_paysOutMissedPayout_onlyDeliveredAndReleasedOrCash() throws Exception {
        UUID cashOld = missedCandidate(PaymentMethod.CASH, true, "40 minutes", false);
        UUID cardReleased = missedCandidate(PaymentMethod.STRIPE, true, "40 minutes", true);
        UUID cardNotReleased = missedCandidate(PaymentMethod.STRIPE, true, "40 minutes", false);
        UUID cashRecent = missedCandidate(PaymentMethod.CASH, true, "5 minutes", false);
        UUID cashNotDelivered = missedCandidate(PaymentMethod.CASH, false, "40 minutes", false);

        List<UUID> found = amendmentRepository.findCardIdsAwaitingMissedPayout(utcNow().minusMinutes(30),
                BidAmendmentPayouts.BLOCKED_ALERT_PREFIX, 1000);

        assertThat(found).contains(cashOld, cardReleased)
                .doesNotContain(cardNotReleased, cashRecent, cashNotDelivered);

        when(gateway.retrieveCharge(anyString())).thenReturn(new ChargeView(false, 0L));
        when(gateway.findTransferByGroup(anyString())).thenReturn(Optional.empty());
        // Un Transfer par modification (uq_bid_amendments_stripe_transfer).
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString())).thenAnswer(inv -> "tr_" + UUID.randomUUID());
        job.run();

        assertThat(column(cashOld, "status")).isEqualTo("PAID_OUT");
        assertThat(column(cardReleased, "status")).isEqualTo("PAID_OUT");
        assertThat(column(cardNotReleased, "status")).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("filet : un versement déjà retenu (alerte ouverte) passe après les autres, jamais de famine")
    void findCardIdsAwaitingMissedPayout_blockedLast() throws Exception {
        UUID blocked = missedCandidate(PaymentMethod.CASH, true, "50 minutes", false);
        UUID fresh = missedCandidate(PaymentMethod.CASH, true, "40 minutes", false);
        jdbc.update("INSERT INTO admin_alerts (id, type, resolved, severity, created_at) "
                + "VALUES (gen_random_uuid(), ?, false, 'WARN', now())", "AMEND_PAYOUT_BLOCKED_" + blocked);

        List<UUID> found = amendmentRepository.findCardIdsAwaitingMissedPayout(utcNow().minusMinutes(30),
                BidAmendmentPayouts.BLOCKED_ALERT_PREFIX, 1000);

        assertThat(found).contains(blocked, fresh);
        assertThat(found.indexOf(fresh)).as("jamais retenue d'abord").isLessThan(found.indexOf(blocked));
    }

    @Test
    @DisplayName("filet : remboursements carte en suspens et captures tardives non rendues, au-delà de 10 min")
    void findCardIdsToRefund_selectsPendingAndLateCaptures() throws Exception {
        UUID pendingOld = settled("REFUND_PENDING", "20 minutes", null, null);
        UUID submitted = settled("REFUND_PENDING", "30 minutes", "'re_sent'", null);
        UUID lateCapture = settled("CANCELLED", "20 minutes", null, null);
        UUID pendingRecent = settled("REFUND_PENDING", "0 minutes", null, null);
        UUID refundedLate = settled("CANCELLED", "20 minutes", "'re_done'", "now()");
        UUID refunded = settled("REFUNDED", "20 minutes", "'re_ok'", "now()");
        UUID declinedUncaptured = settled("DECLINED", "20 minutes", null, null);
        jdbc.update("UPDATE bid_amendments SET stripe_charge_id = NULL WHERE id = ?", declinedUncaptured);

        List<UUID> found = amendmentRepository.findCardIdsToRefund(BidAmendmentCardLedger.CLOSED_UNACCEPTED,
                utcNow().minusMinutes(10), org.springframework.data.domain.PageRequest.of(0, 1000));

        assertThat(found).contains(pendingOld, submitted, lateCapture)
                .doesNotContain(pendingRecent, refundedLate, refunded, declinedUncaptured);
        assertThat(found.indexOf(pendingOld)).as("jamais soumis d'abord").isLessThan(found.indexOf(submitted));

        String pi = column(pendingOld, "stripe_payment_intent_id");
        when(gateway.findRefund(anyString(), anyString())).thenReturn(Optional.empty());
        when(gateway.createRefund(anyString(), anyMap(), anyString())).thenReturn("re_job");
        job.run();
        verify(gateway).createRefund(eq(pi), anyMap(), eq("amendment-refund-" + pendingOld));
    }

    @Test
    @DisplayName("filet : versement resté PAYOUT_PENDING repris, Transfer retrouvé par son groupe, jamais recréé")
    void job_resumesPayoutPending_findsTransferByGroup() throws Exception {
        UUID old = settled("PAYOUT_PENDING", "20 minutes", null, null);
        UUID recent = settled("PAYOUT_PENDING", "0 minutes", null, null);
        when(gateway.findTransferByGroup("amendment_" + old)).thenReturn(Optional.of("tr_found"));

        assertThat(amendmentRepository.findCardIdsPayoutPendingBefore(utcNow().minusMinutes(10),
                org.springframework.data.domain.PageRequest.of(0, 1000))).contains(old).doesNotContain(recent);

        job.run();

        assertThat(column(old, "status")).isEqualTo("PAID_OUT");
        assertThat(column(old, "stripe_transfer_id")).isEqualTo("tr_found");
        assertThat(column(recent, "status")).isEqualTo("PAYOUT_PENDING");
        verify(gateway, never()).createTransfer(anyLong(), anyString(), anyString(), anyString(),
                eq("amendment_" + old), anyMap(), anyString());
    }

    /** Modification carte acceptée, colis livré ou non il y a {@code age}, principal versé ou non. */
    private UUID missedCandidate(PaymentMethod principal, boolean delivered, String age, boolean released)
            throws Exception {
        Parties p = parties(principal);
        UUID id = acceptedCard(p, pi(), "ch_" + UUID.randomUUID());
        jdbc.update("UPDATE bids SET status = ?, updated_at = updated_at - interval '" + age + "' WHERE id = ?",
                delivered ? "COMPLETED" : "ACCEPTED", p.bidId);
        jdbc.update("UPDATE bid_amendments SET updated_at = updated_at - interval '" + age + "' WHERE id = ?", id);
        if (released) {
            persistReleasedPrincipal(p.bidId);
        }
        return id;
    }

    /** Modification carte acceptée, amenée par SQL au statut voulu, modifiée il y a {@code age}. */
    private UUID settled(String status, String age, String refundId, String refundedAt) throws Exception {
        Parties p = parties(PaymentMethod.STRIPE);
        UUID id = acceptedCard(p, pi(), "ch_" + UUID.randomUUID());
        jdbc.update("UPDATE bid_amendments SET status = ?, stripe_refund_id = " + (refundId == null ? "NULL" : refundId)
                + ", refunded_at = " + (refundedAt == null ? "NULL" : refundedAt)
                + ", updated_at = updated_at - interval '" + age + "' WHERE id = ?", status, id);
        return id;
    }

    private void persistReleasedPrincipal(UUID bidId) {
        tx.executeWithoutResult(status -> {
            com.yadony.api.payments.PaymentEntity payment = new com.yadony.api.payments.PaymentEntity();
            payment.setBidId(bidId);
            payment.setAmount(new BigDecimal("22.40"));
            payment.setCommissionAmount(new BigDecimal("2.40"));
            payment.setCurrency("EUR");
            payment.setStatus(com.yadony.api.payments.PaymentStatus.RELEASED);
            payment.setStripePaymentIntentId("pi_principal_" + UUID.randomUUID());
            payment.setEscrowReleasedAt(utcNow().minusHours(1));
            entityManager.persist(payment);
            com.yadony.api.payments.StripeReleaseIntentEntity release =
                    new com.yadony.api.payments.StripeReleaseIntentEntity();
            release.setPaymentId(payment.getId());
            release.setKind(com.yadony.api.payments.StripeReleaseIntentEntity.Kind.TRANSFER);
            release.setStatus(com.yadony.api.payments.StripeReleaseIntentEntity.Status.DONE);
            release.setSource("delivery");
            release.setAuditAction("PAYMENT_RELEASED");
            release.setBidId(bidId);
            release.setAmountMinor(2000L);
            release.setCurrency("eur");
            release.setDestination("acct_principal");
            release.setLeaseUntil(utcNow());
            entityManager.persist(release);
        });
    }

    private static java.time.LocalDateTime utcNow() {
        return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID announcementId, UUID bidId) {
    }

    /** Trajet carte et espèces à 10 €/kg, 6 kg libres, voyageur au compte Connect complet. */
    private Parties parties(PaymentMethod principal) {
        UserEntity traveler = persistUser(true);
        UserEntity sender = persistUser(false);
        UUID announcementId = persistAnnouncement(traveler.getId());
        UUID bidId = persistBid(announcementId, sender.getId(), principal);
        return new Parties(traveler, sender, announcementId, bidId);
    }

    /** Modification de 3 kg par carte créée, autorisée (webhook), puis acceptée (capture) : ACCEPTED. */
    private UUID acceptedCard(Parties p, String piId, String chargeId) throws Exception {
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(view(piId, "requires_payment_method", null));
        UUID id = flow.create(p.bidId, p.sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal("3"), null, PaymentMethod.STRIPE)).amendment().id();
        assertThat(dispatcher.dispatch(event("payment_intent.amount_capturable_updated", "{\"id\":\"" + piId
                + "\",\"object\":\"payment_intent\",\"status\":\"requires_capture\",\"amount\":3360,"
                + "\"amount_capturable\":3360,\"metadata\":{\"bid_amendment_id\":\"" + id + "\",\"bid_id\":\""
                + p.bidId + "\",\"scope\":\"BID_AMENDMENT\"}}"))).isTrue();
        when(gateway.capturePaymentIntent(piId, "amendment-capture-" + id)).thenReturn(view(piId, "succeeded",
                chargeId));
        assertThat(flow.accept(p.bidId, id, p.traveler.getFirebaseUid(), new AmendmentAcceptRequest(null, null))
                .amendment().status()).isEqualTo("ACCEPTED");
        return id;
    }

    private void stubTransfer(String chargeId, String transferId) throws Exception {
        when(gateway.retrieveCharge(chargeId)).thenReturn(new ChargeView(false, 0L));
        when(gateway.findTransferByGroup(anyString())).thenReturn(Optional.empty());
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), eq(chargeId), any(), anyMap(), anyString()))
                .thenReturn(transferId);
    }

    /** Colis livré puis principal carte versé : événement publié dans une transaction, comme l'exécuteur. */
    private void release(Parties p) {
        jdbc.update("UPDATE bids SET status = 'COMPLETED' WHERE id = ?", p.bidId);
        tx.executeWithoutResult(s -> publisher.publishEvent(PaymentReleasedEvent.card(p.bidId, p.traveler.getId(),
                p.sender.getId(), new BigDecimal("22.40"), new BigDecimal("2.40"), "EUR")));
    }

    /** Livraison confirmée (scan du destinataire) : événement publié dans une transaction, comme le suivi. */
    private void deliver(Parties p) {
        jdbc.update("UPDATE bids SET status = 'COMPLETED' WHERE id = ?", p.bidId);
        tx.executeWithoutResult(s -> publisher.publishEvent(new DeliveryConfirmedEvent(p.bidId, p.sender.getId(),
                p.traveler.getId())));
    }

    private static String pi() {
        return "pi_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static PaymentIntentView view(String piId, String status, String chargeId) {
        return new PaymentIntentView(piId, status, 3360L, "eur", piId + "_secret", chargeId, List.of("card"));
    }

    private static String event(String type, String objectJson) {
        return "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"object\":\"event\",\"api_version\":\""
                + com.stripe.Stripe.API_VERSION + "\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson
                + "}}";
    }

    private String column(UUID id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM bid_amendments WHERE id = ?", String.class, id);
    }

    private List<String> auditActions(UUID id) {
        return jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY created_at, action",
                String.class, id);
    }

    private void awaitColumn(UUID id, String column, String expected) throws InterruptedException {
        Supplier<String> value = () -> column(id, column);
        for (int i = 0; i < 100 && !expected.equals(value.get()); i++) {
            Thread.sleep(100);
        }
        assertThat(value.get()).isEqualTo(expected);
    }

    private UserEntity persistUser(boolean cardReady) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("amendment-payout-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeCustomerId("cus_" + UUID.randomUUID());
        if (cardReady) {
            user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            user.setStripeAccountId("acct_" + UUID.randomUUID().toString().substring(0, 12));
        } else {
            user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        }
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
            announcement.setAvailableKg(new BigDecimal("6.00"));
            announcement.setTotalKg(new BigDecimal("60.00"));
            announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            announcement.setPricePerKg(new BigDecimal("10.00"));
            announcement.setCurrency("EUR");
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH, PaymentMethod.STRIPE));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    /** Colis accepté de 2 kg, principal carte (sous séquestre) ou espèces (commission déjà prélevée). */
    private UUID persistBid(UUID announcementId, UUID senderId, PaymentMethod principal) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(principal);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("2"));
            bid.setCommissionRate(new BigDecimal("0.12"));
            bid.setStatus(BidStatus.ACCEPTED);
            if (principal == PaymentMethod.CASH) {
                bid.setCommissionStatus(CommissionStatus.CHARGED);
                bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                bid.setCommissionPaymentIntentId("pi_principal_" + UUID.randomUUID());
            }
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
