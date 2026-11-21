package com.yadony.api.payments.integrity;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La sonde sur Postgres embarqué avec toutes les migrations Flyway : chaque invariant est une
 * requête SQL écrite contre le schéma réel, une colonne renommée doit casser ce test et non la
 * sonde en production.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class MoneyIntegrityMonitorIT {

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

    @Autowired private MoneyIntegrityMonitor monitor;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletAccountRepository walletAccountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoBean private AdminAlertEscalator alertEscalator;

    @AfterEach
    void cleanDb() {
        jdbcTemplate.update("DELETE FROM wallet_accounts");
        // Grand livre en ajout seul (V309) : DELETE refusé, TRUNCATE permis pour les tests.
        // CASCADE : des tables y font référence (vides dans ce test).
        jdbcTemplate.execute("TRUNCATE wallet_transactions CASCADE");
        jdbcTemplate.execute("TRUNCATE bids CASCADE");
        jdbcTemplate.execute("TRUNCATE announcements CASCADE");
    }

    /**
     * Commission jamais rendue sur un colis clos sans livraison : annulé (toujours), absence du
     * voyageur depuis la règle du 19/10, et colis issu d'une négociation (exclu jusqu'ici). Une
     * absence antérieure à la règle, où la commission restait acquise, n'est pas signalée.
     */
    @Test
    void inv10_commissionNonRendueSurUnColisClos_signaleeAussiPourLAbsenceEtLaNegociation() {
        UUID travelerId = persistUser();
        UUID senderId = persistUser();
        UUID announcementId = persistAnnouncement(travelerId);
        UUID cancelled = persistChargedCashBid(announcementId, senderId, BidStatus.CANCELLED);
        UUID noShowAfterRule = persistChargedCashBid(announcementId, senderId, BidStatus.NO_SHOW);
        UUID noShowBeforeRule = persistChargedCashBid(announcementId, senderId, BidStatus.NO_SHOW);
        UUID negotiated = persistChargedCashBid(announcementId, senderId, BidStatus.PARCEL_REFUSED);
        UUID delivered = persistChargedCashBid(announcementId, senderId, BidStatus.COMPLETED);
        ageBid(cancelled, "now() - interval '2 hours'");
        ageBid(noShowAfterRule, "now() - interval '2 hours'");
        ageBid(noShowBeforeRule, "TIMESTAMP '2026-10-10 12:00:00'");
        ageBid(negotiated, "now() - interval '2 hours'");
        ageBid(delivered, "now() - interval '2 hours'");
        linkToNegotiationThread(negotiated);

        MoneyIntegrityMonitor.Inspection inspection = monitor.inspect("INV-10", 50).orElseThrow();

        assertThat(inspection.rows())
                .extracting(r -> String.valueOf(r.get("bid_id")))
                .containsExactlyInAnyOrder(cancelled.toString(), noShowAfterRule.toString(), negotiated.toString());
        assertThat(inspection.rows()).allSatisfy(r -> assertThat(r).containsEntry("anomaly", "CHARGEE_SUR_BID_ANNULE"));
    }

    private UUID persistAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport de Dakar");
        a.setDeliveryLat(new BigDecimal("14.670833"));
        a.setDeliveryLng(new BigDecimal("-17.073056"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("10"));
        a.setCurrency("EUR");
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        return announcementRepository.saveAndFlush(a).getId();
    }

    /** Commission carte prélevée (PaymentIntent présent) : aucune autre anomalie INV-10 en jeu. */
    private UUID persistChargedCashBid(UUID announcementId, UUID senderId, BidStatus status) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCurrency("EUR");
        bid.setWeightKg(new BigDecimal("5"));
        bid.setStatus(status);
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(CommissionChargedVia.CARD);
        bid.setCommissionPaymentIntentId("pi_inv10_" + UUID.randomUUID());
        return bidRepository.saveAndFlush(bid).getId();
    }

    private void ageBid(UUID bidId, String updatedAtSql) {
        jdbcTemplate.update("UPDATE bids SET updated_at = " + updatedAtSql + " WHERE id = ?", bidId);
    }

    /**
     * Lien vers un fil de négociation sans créer le fil : la clé étrangère est suspendue le temps
     * de cette seule transaction (base embarquée du test, utilisateur superviseur).
     */
    private void linkToNegotiationThread(UUID bidId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = 'replica'");
            jdbcTemplate.update("UPDATE bids SET linked_negotiation_thread_id = ? WHERE id = ?", UUID.randomUUID(), bidId);
        });
    }

    @Test
    void inv02_deuxMouvementsDeMemeHorodatage_suiventLOrdreDInsertion() {
        // Deux mouvements écrits dans la même transaction partagent created_at. L'id du
        // second est choisi plus petit que celui du premier : trié par (created_at, id),
        // l'ancien ordre lisait le second avant le premier et voyait une fausse rupture.
        UUID userId = persistUser();
        java.sql.Timestamp sameInstant = java.sql.Timestamp.from(java.time.Instant.parse("2026-10-19T10:00:00Z"));
        String insert = "INSERT INTO wallet_transactions (id, user_id, currency, type, amount, balance_after, created_at)"
                + " VALUES (?, ?, 'EUR', ?, ?, ?, ?)";
        jdbcTemplate.update(insert, UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"), userId, "TOP_UP",
                new BigDecimal("10.00"), new BigDecimal("10.00"), sameInstant);
        jdbcTemplate.update(insert, UUID.fromString("00000000-0000-4000-8000-000000000000"), userId, "TOP_UP",
                new BigDecimal("5.00"), new BigDecimal("15.00"), sameInstant);

        // Portefeuille aligné sur le grand livre : INV-01 reste à 0. La sonde garde le dernier
        // compte de chaque règle entre deux passages (bean partagé par les tests) : un INV-01 à 1
        // ici empêcherait l'alerte attendue par unSoldeSansMouvement… si elle passe après.
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("15.00"));
        walletAccountRepository.saveAndFlush(wallet);

        Map<String, Long> counts = monitor.runAll();
        assertThat(counts).containsEntry("INV-02", 0L).containsEntry("INV-01", 0L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT seq FROM wallet_transactions WHERE user_id = ? ORDER BY seq", Long.class, userId))
                .hasSize(2).isSorted();
    }

    /**
     * Retenue d'annulation réglée sur une carte : 70 rendus sur 100 payés est l'état normal (30 retenus), pas un
     * remboursement Stripe inachevé. Un remboursement partiel sans retenue au montant exact reste signalé.
     */
    @Test
    void inv07_retenueDAnnulationAuMontantExact_nEstPasUnRemboursementNonConfirme() {
        UUID travelerId = persistUser();
        UUID senderId = persistUser();
        UUID announcementId = persistAnnouncement(travelerId);
        UUID retainedBid = persistCardBid(announcementId, senderId);
        UUID threadBid = persistCardBid(announcementId, senderId);
        UUID noFeeBid = persistCardBid(announcementId, senderId);
        UUID mismatchBid = persistCardBid(announcementId, senderId);
        UUID dueBid = persistCardBid(announcementId, senderId);

        UUID retained = persistRefundedCardPayment(retainedBid, "100.00", "70.00");
        persistFee(retainedBid, senderId, travelerId, "RETAINED", "30.00");
        UUID viaThread = persistRefundedCardPayment(threadBid, "100.00", "70.00");
        persistFee(threadBid, senderId, travelerId, "PAID_OUT", "30.00");
        moveToNegotiationThread(viaThread, threadBid);
        UUID noFee = persistRefundedCardPayment(noFeeBid, "100.00", "70.00");
        UUID mismatch = persistRefundedCardPayment(mismatchBid, "100.00", "50.00");
        persistFee(mismatchBid, senderId, travelerId, "PAYOUT_PENDING", "30.00");
        UUID dueOnly = persistRefundedCardPayment(dueBid, "100.00", "70.00");
        persistFee(dueBid, senderId, travelerId, "DUE", "0");
        for (UUID paymentId : java.util.List.of(retained, viaThread, noFee, mismatch, dueOnly)) {
            auditPaymentAt(paymentId, "now() - interval '2 days'");
        }

        MoneyIntegrityMonitor.Inspection inspection = monitor.inspect("INV-07", 50).orElseThrow();

        assertThat(inspection.rows())
                .filteredOn(r -> "REFUND_STRIPE_NON_CONFIRME".equals(r.get("anomaly")))
                .extracting(r -> String.valueOf(r.get("payment_id")))
                .containsExactlyInAnyOrder(noFee.toString(), mismatch.toString(), dueOnly.toString());
    }

    /** Retenue jamais réglée alors que l'expéditeur est remboursé depuis plus de 24 h. */
    @Test
    void inv19_retenueRestéeDueSurUnPaiementRembourseDepuisPlusDe24h() {
        UUID travelerId = persistUser();
        UUID senderId = persistUser();
        UUID announcementId = persistAnnouncement(travelerId);
        UUID staleBid = persistCardBid(announcementId, senderId);
        UUID recentBid = persistCardBid(announcementId, senderId);
        UUID escrowBid = persistCardBid(announcementId, senderId);

        UUID stalePayment = persistRefundedCardPayment(staleBid, "100.00", null);
        UUID stale = persistFee(staleBid, senderId, travelerId, "DUE", "0");
        ageFee(stale, "now() - interval '2 days'");
        auditPaymentAt(stalePayment, "now() - interval '2 days'");
        UUID recentPayment = persistRefundedCardPayment(recentBid, "100.00", null);
        UUID recent = persistFee(recentBid, senderId, travelerId, "DUE", "0");
        ageFee(recent, "now() - interval '2 days'");
        auditPaymentAt(recentPayment, "now() - interval '1 hour'");
        persistEscrowCardPayment(escrowBid);
        UUID escrow = persistFee(escrowBid, senderId, travelerId, "DUE", "0");
        ageFee(escrow, "now() - interval '2 days'");

        assertThat(inv19Anomalies())
                .containsOnly(Map.entry(stale.toString(), "RETENUE_DUE_SUR_PAIEMENT_REMBOURSE"));
    }

    /** Dédommagement toujours pas versé 3 jours après la retenue (réglée ou en attente) ; versé : rien. */
    @Test
    void inv19_dedommagementNonVerseTroisJoursApresLaRetenue() {
        UUID travelerId = persistUser();
        UUID senderId = persistUser();
        UUID announcementId = persistAnnouncement(travelerId);
        UUID oldRetainedBid = persistCardBid(announcementId, senderId);
        UUID oldPendingBid = persistCardBid(announcementId, senderId);
        UUID freshRetainedBid = persistCardBid(announcementId, senderId);
        UUID oldPaidBid = persistCardBid(announcementId, senderId);

        persistRefundedCardPayment(oldRetainedBid, "100.00", "70.00");
        UUID oldRetained = persistFee(oldRetainedBid, senderId, travelerId, "RETAINED", "30.00");
        auditRetainedAt(oldRetainedBid, "now() - interval '4 days'");
        persistRefundedCardPayment(oldPendingBid, "100.00", "70.00");
        UUID oldPending = persistFee(oldPendingBid, senderId, travelerId, "PAYOUT_PENDING", "30.00");
        ageFee(oldPending, "now() - interval '4 days'");
        auditRetainedAt(oldPendingBid, "now() - interval '5 days'");
        persistRefundedCardPayment(freshRetainedBid, "100.00", "70.00");
        UUID freshRetained = persistFee(freshRetainedBid, senderId, travelerId, "RETAINED", "30.00");
        // Ligne créée il y a 4 jours (annulation), réglée il y a 1 heure : l'audit de la retenue fait foi.
        ageFee(freshRetained, "now() - interval '4 days'");
        auditRetainedAt(freshRetainedBid, "now() - interval '1 hour'");
        persistRefundedCardPayment(oldPaidBid, "100.00", "70.00");
        UUID oldPaid = persistFee(oldPaidBid, senderId, travelerId, "PAID_OUT", "30.00");
        auditRetainedAt(oldPaidBid, "now() - interval '5 days'");

        // oldPaid : versé, rien à signaler même 5 jours après la retenue.
        assertThat(inv19Anomalies()).doesNotContainKey(oldPaid.toString()).containsOnly(
                Map.entry(oldRetained.toString(), "DEDOMMAGEMENT_NON_VERSE"),
                Map.entry(oldPending.toString(), "DEDOMMAGEMENT_NON_VERSE"));
    }

    /**
     * Retenue réglée : remboursé + brut retenu = payé, sinon l'expéditeur a été remboursé de trop (ou de trop peu)
     * alors que le voyageur est (ou sera) dédommagé. Le paiement d'un fil de négociation compte comme celui du colis.
     * Règle CRITIQUE à part : son alerte part même quand INV-19 (retard de versement) est déjà en faute.
     */
    @Test
    void inv20_retenueRegleeIncoherenteAvecLeMontantRembourse_alerteCritiqueAPart() {
        UUID travelerId = persistUser();
        UUID senderId = persistUser();
        UUID announcementId = persistAnnouncement(travelerId);
        UUID exactBid = persistCardBid(announcementId, senderId);
        UUID threadBid = persistCardBid(announcementId, senderId);
        UUID fullRefundBid = persistCardBid(announcementId, senderId);
        UUID notRecordedBid = persistCardBid(announcementId, senderId);
        UUID noPaymentBid = persistCardBid(announcementId, senderId);

        persistRefundedCardPayment(exactBid, "100.00", "70.00");
        persistFee(exactBid, senderId, travelerId, "PAID_OUT", "30.00");
        UUID viaThread = persistRefundedCardPayment(threadBid, "100.00", "70.00");
        persistFee(threadBid, senderId, travelerId, "RETAINED", "30.00");
        moveToNegotiationThread(viaThread, threadBid);
        persistRefundedCardPayment(fullRefundBid, "100.00", "100.00");
        UUID fullRefund = persistFee(fullRefundBid, senderId, travelerId, "PAID_OUT", "30.00");
        persistRefundedCardPayment(notRecordedBid, "100.00", null);
        UUID notRecorded = persistFee(notRecordedBid, senderId, travelerId, "PAYOUT_PENDING", "30.00");
        UUID noPayment = persistFee(noPaymentBid, senderId, travelerId, "RETAINED", "30.00");
        // Retard de versement au montant exact : INV-19 seulement.
        UUID lateBid = persistCardBid(announcementId, senderId);
        persistRefundedCardPayment(lateBid, "100.00", "70.00");
        UUID late = persistFee(lateBid, senderId, travelerId, "RETAINED", "30.00");
        auditRetainedAt(lateBid, "now() - interval '4 days'");

        MoneyIntegrityMonitor.Inspection inspection = monitor.inspect("INV-20", 50).orElseThrow();
        assertThat(inspection.severity()).isEqualTo("CRITIQUE");
        Map<String, String> byFee = new java.util.HashMap<>();
        inspection.rows().forEach(r ->
                byFee.put(String.valueOf(r.get("cancellation_fee_id")), String.valueOf(r.get("anomaly"))));
        assertThat(byFee).containsOnly(
                Map.entry(fullRefund.toString(), "RETENUE_INCOHERENTE_AVEC_LE_REMBOURSEMENT"),
                Map.entry(notRecorded.toString(), "RETENUE_INCOHERENTE_AVEC_LE_REMBOURSEMENT"),
                Map.entry(noPayment.toString(), "RETENUE_INCOHERENTE_AVEC_LE_REMBOURSEMENT"));
        assertThat(inv19Anomalies()).containsOnly(Map.entry(late.toString(), "DEDOMMAGEMENT_NON_VERSE"));

        Map<String, Long> counts = monitor.runAll();

        assertThat(counts).containsEntry("INV-19", 1L).containsEntry("INV-20", 3L);
        verify(alertEscalator).raiseOnce(eq("MONEY_INVARIANT_INV-20"), eq("CRITICAL"), anyString(), anyMap());
        verify(alertEscalator).raiseOnce(eq("MONEY_INVARIANT_INV-19"), eq("WARN"), anyString(), anyMap());
    }

    private Map<String, String> inv19Anomalies() {
        MoneyIntegrityMonitor.Inspection inspection = monitor.inspect("INV-19", 50).orElseThrow();
        assertThat(inspection.severity()).isEqualTo("HAUTE");
        Map<String, String> byFee = new java.util.HashMap<>();
        inspection.rows().forEach(r ->
                byFee.put(String.valueOf(r.get("cancellation_fee_id")), String.valueOf(r.get("anomaly"))));
        return byFee;
    }

    private UUID persistCardBid(UUID announcementId, UUID senderId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setCurrency("EUR");
        bid.setWeightKg(new BigDecimal("5"));
        bid.setStatus(BidStatus.CANCELLED);
        return bidRepository.saveAndFlush(bid).getId();
    }

    /** Paiement carte capturé puis remboursé ; {@code refunded} nul : montant remboursé jamais enregistré. */
    private UUID persistRefundedCardPayment(UUID bidId, String amount, String refunded) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payments (id, bid_id, stripe_payment_intent_id, amount, commission_amount, currency,
                                      status, rail, captured_at, refunded_amount)
                VALUES (?, ?, ?, ?, 4.76, 'EUR', 'REFUNDED', 'STRIPE', now() - interval '3 days', ?)
                """, id, bidId, "pi_fee_" + UUID.randomUUID(), new BigDecimal(amount),
                refunded == null ? null : new BigDecimal(refunded));
        return id;
    }

    private void persistEscrowCardPayment(UUID bidId) {
        jdbcTemplate.update("""
                INSERT INTO payments (id, bid_id, stripe_payment_intent_id, amount, commission_amount, currency,
                                      status, rail, captured_at)
                VALUES (?, ?, ?, 100.00, 4.76, 'EUR', 'ESCROW', 'STRIPE', now() - interval '3 days')
                """, UUID.randomUUID(), bidId, "pi_fee_" + UUID.randomUUID());
    }

    private UUID persistFee(UUID bidId, UUID senderId, UUID travelerId, String status, String grossFee) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO cancellation_fees (id, bid_id, sender_id, traveler_id, rate, reason, status, gross_fee,
                                               refund_fees, commission, traveler_net, currency)
                VALUES (?, ?, ?, ?, 0.30, 'SENDER_CANCEL', ?, ?, 0, 0, 0, 'EUR')
                """, id, bidId, senderId, travelerId, status, new BigDecimal(grossFee));
        return id;
    }

    private void ageFee(UUID feeId, String createdAtSql) {
        jdbcTemplate.update("UPDATE cancellation_fees SET created_at = " + createdAtSql + " WHERE id = ?", feeId);
    }

    private void auditPaymentAt(UUID paymentId, String createdAtSql) {
        jdbcTemplate.update("INSERT INTO audit_log (entity_type, entity_id, action, created_at) VALUES "
                + "('PAYMENT', ?, 'BID_CANCELLED_REFUND', " + createdAtSql + ")", paymentId);
    }

    private void auditRetainedAt(UUID bidId, String createdAtSql) {
        jdbcTemplate.update("INSERT INTO audit_log (entity_type, entity_id, action, created_at) VALUES "
                + "('BID', ?, 'CANCELLATION_FEE_RETAINED', " + createdAtSql + ")", bidId);
    }

    /**
     * Paiement d'un fil de négociation : rattaché au fil (le colis matérialisé y pointe), plus au colis. Le fil n'est
     * pas créé, la clé étrangère est suspendue le temps de cette seule transaction (comme
     * {@link #linkToNegotiationThread}).
     */
    private void moveToNegotiationThread(UUID paymentId, UUID bidId) {
        UUID threadId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = 'replica'");
            jdbcTemplate.update("UPDATE bids SET linked_negotiation_thread_id = ? WHERE id = ?", threadId, bidId);
            jdbcTemplate.update("UPDATE payments SET bid_id = NULL, negotiation_thread_id = ? WHERE id = ?",
                    threadId, paymentId);
        });
    }

    @Test
    void lesVingtInvariantsTournentSurLeSchemaReelEtSontAZeroSurUneBaseVide() {
        Map<String, Long> counts = monitor.runAll();

        assertThat(counts).hasSize(MoneyInvariants.ALL.size());
        assertThat(MoneyInvariants.ALL).hasSize(20);
        assertThat(counts.values()).allSatisfy(n -> assertThat(n).isZero());
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unSoldeSansMouvementEstDetecteAlerteUneFoisEtExposeAPrometheus() {
        // Solde de 10 € sans aucune ligne au grand livre : de l'argent apparu de nulle part.
        UUID userId = persistUser();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("10.00"));
        walletAccountRepository.saveAndFlush(wallet);

        Map<String, Long> first = monitor.runAll();
        Map<String, Long> second = monitor.runAll();

        assertThat(first).containsEntry("INV-01", 1L);
        assertThat(second).containsEntry("INV-01", 1L);
        // Une seule alerte : le compte n'a pas augmenté entre les deux passages.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(alertEscalator, times(1)).raiseOnce(eq("MONEY_INVARIANT_INV-01"), eq("CRITICAL"), anyString(),
                context.capture());
        // L'alerte embarque la ligne fautive : l'admin sait quel wallet corriger.
        assertThat(context.getValue().get("exemples")).asString().contains(wallet.getId().toString());
        assertThat(meterRegistry.get("yadony.money.invariant.violations")
                .tag("invariant", "INV-01").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void inspectReExecuteLaRegleEtRendLesLignesEnFaute() {
        UUID userId = persistUser();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("10.00"));
        walletAccountRepository.saveAndFlush(wallet);

        MoneyIntegrityMonitor.Inspection inspection = monitor.inspect("INV-01", 50).orElseThrow();

        assertThat(inspection.total()).isEqualTo(1L);
        assertThat(inspection.severity()).isEqualTo("CRITIQUE");
        assertThat(inspection.rows()).hasSize(1);
        assertThat(inspection.rows().get(0)).containsEntry("wallet_id", wallet.getId().toString());
        assertThat(monitor.inspect("INV-99", 50)).isEmpty();
    }

    private UUID persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("money-integrity-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user).getId();
    }
}
