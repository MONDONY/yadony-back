package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeEventDispatcher;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.integrity.MoneyIntegrityMonitor;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Rail espèces du supplément de bout en bout, sur un vrai PostgreSQL (V338 : contraintes du
 * rail espèces, types du grand livre, index unique), Stripe simulé.
 * <ul>
 *   <li>solde : commission du supplément prélevée à la demande en {@code SUPPLEMENT_COMMISSION_DEDUCTED},
 *       {@code bids.commission_*} intacts, acceptation en main propre, puis colis annulé : la
 *       commission principale ET celle du supplément sont rendues, chacune une fois, et la sonde
 *       d'intégrité reste à zéro (INV-01, 02, 03, 09, 10 : plan §9 B4 point 3) ;</li>
 *   <li>solde court : 422 avec alternatives, rien n'est créé ni débité ;</li>
 *   <li>carte commission avec 3DS : demande en attente invisible de l'expéditeur, webhook de
 *       commission routé vers le rail espèces (jamais vers la commission du colis), retrait, Refund.</li>
 * </ul>
 */
@SpringBootTest(properties = {"yadony.bid-supplement.enabled=true", "yadony.bid-supplement.reconcile-enabled=false"})
@ActiveProfiles("e2e")
class BidSupplementCashIT {

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
    @MockitoBean AdminAlertEscalator alerts;

    @Autowired BidSupplementService supplementService;
    @Autowired BidSupplementPaymentFlow paymentFlow;
    @Autowired StripeEventDispatcher dispatcher;
    @Autowired CashCommissionService cashCommissionService;
    @Autowired WalletService walletService;
    @Autowired MoneyIntegrityMonitor monitor;
    @Autowired UserRepository userRepository;
    @Autowired BidRepository bidRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired BidSupplementCashReconciliationJob cashJob;

    @Test
    void solde_commissionDuSupplementPuisColisAnnule_toutRendu_sansFauxPositifDIntegrite() throws Exception {
        Parties p = parties("pm_unused");
        topUp(p.traveler.getId(), "10.00");
        chargePrincipalCommissionFromWallet(p, "2.00");

        BidSupplementResponse created = paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(), request(p, null));

        assertThat(created.status()).isEqualTo("PROPOSED");
        assertThat(created.commissionStatus()).isEqualTo("CHARGED");
        BigDecimal commission = created.commissionAmount();
        assertThat(commission).isPositive();
        UUID supplementId = created.id();
        Map<String, Object> line = jdbc.queryForMap("SELECT type, amount, bid_id, payment_ref, idempotency_key "
                + "FROM wallet_transactions WHERE payment_ref = ?", "supplement-" + supplementId);
        assertThat(line).containsEntry("type", "SUPPLEMENT_COMMISSION_DEDUCTED")
                .containsEntry("bid_id", p.bidId)
                .containsEntry("idempotency_key", "supplement-commission-" + supplementId);
        assertThat((BigDecimal) line.get("amount")).isEqualByComparingTo(commission.negate());
        // La commission principale du colis n'a pas bougé.
        assertThat(jdbc.queryForMap("SELECT commission_status, commission_charged_via, commission_payment_intent_id "
                + "FROM bids WHERE id = ?", p.bidId))
                .containsEntry("commission_status", "CHARGED")
                .containsEntry("commission_charged_via", "WALLET")
                .containsEntry("commission_payment_intent_id", null);

        SupplementAcceptResponse accepted = paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid());
        assertThat(accepted.rail()).isEqualTo("CASH");
        assertThat(accepted.supplement().status()).isEqualTo("PAID");
        assertThat(accepted.supplement().netAmount()).isNull();
        assertThat(accepted.clientSecret()).isNull();

        // Le colis est annulé : la commission principale est rendue par son propre chemin, qui ne
        // doit jamais rendre aussi celle du supplément (lignes et préfixes distincts).
        tx.executeWithoutResult(status -> {
            BidEntity bid = bidRepository.findById(p.bidId).orElseThrow();
            cashCommissionService.refundCommissionToWallet(bid, p.traveler.getId(), "trip-cancel-" + p.bidId);
            bid.setStatus(BidStatus.CANCELLED);
            bidRepository.save(bid);
        });
        assertThat(sum("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions WHERE type = 'REFUND' "
                + "AND payment_ref = ?", "refund-" + p.bidId)).isEqualByComparingTo("2.00");

        assertThat(supplementService.onBidEnded(p.bidId, "BID_REJECTED")).isTrue();
        assertThat(supplementService.onBidEnded(p.bidId, "BID_REJECTED")).as("rejeu sans effet").isFalse();
        awaitColumn(supplementId, "commission_status", "REFUNDED");
        assertThat(column(supplementId, "status")).isEqualTo("REFUNDED");
        assertThat(sum("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions WHERE type = 'REFUND' "
                + "AND payment_ref = ?", "supplement-refund-" + supplementId)).isEqualByComparingTo(commission);
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("10.00");

        Map<String, Long> counts = monitor.runAll();
        assertThat(counts).containsEntry("INV-01", 0L).containsEntry("INV-02", 0L).containsEntry("INV-03", 0L)
                .containsEntry("INV-09", 0L).containsEntry("INV-10", 0L);
    }

    @Test
    void soldeCourt_422AvecAlternatives_rienCreeNiDebite() {
        Parties p = parties(null);
        topUp(p.traveler.getId(), "0.10");

        Throwable thrown = catchThrowable(() -> paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(),
                request(p, CommissionSource.WALLET_FIRST)));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo("supplement-commission-unfunded");
            assertThat(e.getProperties()).containsKeys("alternatives", "shortfall", "availableBalance",
                    "requiredCommission", "hasCard", "currency", "bidCurrency");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bid_supplements WHERE bid_id = ?", Integer.class,
                p.bidId)).isZero();
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("0.10");
    }

    @Test
    void carteAvec3ds_webhookDeCommission_puisRetrait_commissionRembourseeParStripe() throws Exception {
        Parties p = parties("pm_card");
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        when(gateway.createPaymentIntent(any(), any())).thenReturn(new PaymentIntentView(piId, "requires_action",
                110L, "eur", piId + "_secret", null, List.of("card")));

        BidSupplementResponse created = paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(),
                request(p, CommissionSource.CARD));

        assertThat(created.status()).isEqualTo("COMMISSION_PENDING");
        assertThat(created.commissionClientSecret()).isEqualTo(piId + "_secret");
        assertThat(created.blocksHandover()).isTrue();
        UUID supplementId = created.id();
        assertThat(column(supplementId, "commission_status")).isEqualTo("REQUIRES_3DS");
        assertThat(column(supplementId, "commission_charged_via")).isEqualTo("CARD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, supplementId, "BID_SUPPLEMENT_REQUESTED")).isEqualTo(1);

        String succeeded = event("evt_" + UUID.randomUUID(), "payment_intent.succeeded",
                "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\","
                        + "\"metadata\":{\"bid_supplement_id\":\"" + supplementId + "\","
                        + "\"commission_purpose\":\"bid_supplement_commission\","
                        + "\"scope\":\"BID_SUPPLEMENT_COMMISSION\"}}");
        assertThat(dispatcher.dispatch(succeeded)).isTrue();
        assertThat(dispatcher.dispatch(succeeded)).as("rejeu").isTrue();

        assertThat(column(supplementId, "status")).isEqualTo("PROPOSED");
        assertThat(column(supplementId, "commission_status")).isEqualTo("CHARGED");
        assertThat(column(supplementId, "commission_payment_intent_id")).isEqualTo(piId);
        assertThat(jdbc.queryForMap("SELECT commission_status, commission_payment_intent_id FROM bids WHERE id = ?",
                p.bidId)).as("le webhook de la commission du colis ne l'a pas prise pour la sienne")
                .containsEntry("commission_status", "CHARGED")
                .containsEntry("commission_payment_intent_id", principalPi(p));

        when(gateway.findRefund(piId, supplementId.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), eq("supplement_commission_refund_" + supplementId)))
                .thenReturn("re_comm");
        supplementService.withdraw(p.bidId, supplementId, p.traveler.getFirebaseUid());

        awaitColumn(supplementId, "commission_status", "REFUNDED");
        assertThat(column(supplementId, "status")).isEqualTo("WITHDRAWN");
        assertThat(column(supplementId, "closed_reason")).isEqualTo("WITHDRAWN_BY_TRAVELER");
    }

    @Test
    void carteRefusee_demandeAnnulee_horsPlafond_etNouvelleDemandePossible() throws Exception {
        Parties p = parties("pm_card");
        when(gateway.createPaymentIntent(any(), any())).thenThrow(new com.stripe.exception.CardException(
                "declined", null, "card_declined", null, null, null, null, null));

        assertThat(catchThrowable(() -> paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(),
                request(p, CommissionSource.CARD)))).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("supplement-commission-card-declined"));
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM bid_supplements WHERE bid_id = ?", String.class,
                p.bidId)).isEqualTo("COMMISSION_FAILED");

        assertThat(catchThrowable(() -> paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(),
                request(p, CommissionSource.CARD)))).isInstanceOf(YadonyBusinessException.class);
        topUp(p.traveler.getId(), "10.00");
        BidSupplementResponse third = paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(), request(p, null));
        assertThat(third.status()).as("deux refus de carte ne consomment pas le plafond de 2").isEqualTo("PROPOSED");
    }


    // ── Relecture B4 ─────────────────────────────────────────────────────────

    @Test
    void doublePostSimultaneSurLeSolde_unSeulDebitEtUn409() throws Exception {
        Parties p = parties(null);
        topUp(p.traveler.getId(), "10.00");
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(), request(p, null));
                    } catch (YadonyBusinessException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new java.util.ArrayList<>();
            for (var f : results) {
                outcomes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(o -> o instanceof BidSupplementResponse).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o instanceof YadonyBusinessException).singleElement()
                    .satisfies(o -> assertThat(((YadonyBusinessException) o).getErrorCode())
                            .isEqualTo("supplement-already-active"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ? "
                + "AND type = 'SUPPLEMENT_COMMISSION_DEDUCTED'", Integer.class, p.bidId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bid_supplements WHERE bid_id = ?", Integer.class,
                p.bidId)).isEqualTo(1);
    }

    @Test
    void multidevise_colisXofComplementEur_deuxPortefeuillesDebites_puisRefus_deuxRecredits_integriteVerte()
            throws Exception {
        Parties p = parties(null, "XOF");
        topUp(p.traveler.getId(), "XOF", "300");
        topUp(p.traveler.getId(), "EUR", "10.00");

        BidSupplementResponse created = paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(),
                new CreateSupplementRequest(new BigDecimal("20000"), BidSupplementReason.OTHER, null, null,
                        "tracking/" + p.bidId + "/1_supplement.jpg", CommissionSource.WALLET_FIRST, "EUR"));

        assertThat(created.status()).isEqualTo("PROPOSED");
        assertThat(created.commissionAmount()).isGreaterThan(new BigDecimal("300"));
        UUID supplementId = created.id();
        List<Map<String, Object>> debits = jdbc.queryForList("SELECT currency, amount, source_currency, "
                + "idempotency_key FROM wallet_transactions WHERE payment_ref = ? ORDER BY currency",
                "supplement-" + supplementId);
        assertThat(debits).hasSize(2);
        assertThat(debits.get(0)).containsEntry("currency", "EUR").containsEntry("source_currency", "XOF")
                .containsEntry("idempotency_key", "supplement-commission-" + supplementId + "-active");
        assertThat(debits.get(1)).containsEntry("currency", "XOF")
                .containsEntry("idempotency_key", "supplement-commission-" + supplementId);
        assertThat((BigDecimal) debits.get(1).get("amount")).isEqualByComparingTo("-300");
        assertThat(walletService.getBalance(p.traveler.getId(), "XOF")).isEqualByComparingTo("0");

        supplementService.decline(p.bidId, supplementId, p.sender.getFirebaseUid());

        awaitColumn(supplementId, "commission_status", "REFUNDED");
        List<Map<String, Object>> credits = jdbc.queryForList("SELECT currency, idempotency_key FROM "
                + "wallet_transactions WHERE type = 'REFUND' AND payment_ref = ? ORDER BY currency",
                "supplement-refund-" + supplementId);
        assertThat(credits).extracting(r -> r.get("idempotency_key")).containsExactly(
                "supplement-refund-" + supplementId + "-EUR", "supplement-refund-" + supplementId + "-XOF");
        assertThat(walletService.getBalance(p.traveler.getId(), "XOF")).isEqualByComparingTo("300");
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("10.00");

        Map<String, Long> counts = monitor.runAll();
        assertThat(counts).containsEntry("INV-01", 0L).containsEntry("INV-02", 0L).containsEntry("INV-03", 0L)
                .containsEntry("INV-09", 0L).containsEntry("INV-10", 0L);
    }

    @Test
    void confirmationRejoueeEtWebhook_uneSeuleTransitionEtUneSeuleNotification() throws Exception {
        Parties p = parties("pm_card");
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        when(gateway.createPaymentIntent(any(), any())).thenReturn(new PaymentIntentView(piId, "requires_action",
                110L, "eur", piId + "_secret", null, List.of("card")));
        UUID supplementId = paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(), request(p, CommissionSource.CARD))
                .id();
        when(gateway.retrievePaymentIntent(piId)).thenReturn(new PaymentIntentView(piId, "succeeded", 110L, "eur",
                null, "ch_c", List.of("card")));

        assertThat(paymentFlow.confirmCommission(p.bidId, supplementId, p.traveler.getFirebaseUid()).status())
                .isEqualTo("PROPOSED");
        assertThat(paymentFlow.confirmCommission(p.bidId, supplementId, p.traveler.getFirebaseUid()).status())
                .isEqualTo("PROPOSED");
        assertThat(dispatcher.dispatch(event("evt_" + UUID.randomUUID(), "payment_intent.succeeded",
                "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\","
                        + "\"metadata\":{\"bid_supplement_id\":\"" + supplementId + "\","
                        + "\"commission_purpose\":\"bid_supplement_commission\"}}"))).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, supplementId, "BID_SUPPLEMENT_COMMISSION_CHARGED_CARD")).isEqualTo(1);
        Supplier<Integer> notified = () -> jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? "
                + "AND type = 'BID_SUPPLEMENT_REQUESTED'", Integer.class, p.sender.getId());
        for (int i = 0; i < 100 && notified.get() == 0; i++) {
            Thread.sleep(100);
        }
        Thread.sleep(500);
        assertThat(notified.get()).isEqualTo(1);
    }

    @Test
    void retraitPendantLeDebitDeLaCarte_paymentIntentAnnulee_etFiletRetrouveLaDemandeSiLAnnulationEchoue()
            throws Exception {
        Parties p = parties("pm_card");
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        UUID[] created = new UUID[1];
        when(gateway.createPaymentIntent(any(), any())).thenAnswer(inv -> {
            // Le voyageur retire sa demande pendant que Stripe répond.
            UUID id = jdbc.queryForObject("SELECT id FROM bid_supplements WHERE bid_id = ?", UUID.class, p.bidId);
            created[0] = id;
            supplementService.withdraw(p.bidId, id, p.traveler.getFirebaseUid());
            return new PaymentIntentView(piId, "requires_action", 110L, "eur", piId + "_secret", null,
                    List.of("card"));
        });
        // L'annulation échoue tant que le filet n'est pas passé : l'écouteur asynchrone de clôture peut
        // tourner avant ou après la pose de la PaymentIntent, il ne doit pas consommer une réponse « canceled ».
        when(gateway.cancelPaymentIntent(piId))
                .thenThrow(new com.stripe.exception.ApiConnectionException("timeout"));
        when(gateway.retrievePaymentIntent(piId)).thenThrow(new com.stripe.exception.ApiConnectionException("timeout"));

        BidSupplementResponse r = paymentFlow.create(p.bidId, p.traveler.getFirebaseUid(), request(p, CommissionSource.CARD));

        assertThat(r.status()).isEqualTo("WITHDRAWN");
        assertThat(r.commissionClientSecret()).isNull();
        UUID id = created[0];
        assertThat(column(id, "commission_payment_intent_id")).isEqualTo(piId);
        Thread.sleep(1000); // laisse finir l'écouteur asynchrone de clôture
        assertThat(column(id, "commission_status")).as("annulation en échec : reste à défaire").isNull();
        doReturn(new PaymentIntentView(piId, "canceled", 110L, "eur", null, null, List.of("card")))
                .when(gateway).cancelPaymentIntent(piId);
        // Le filet la retrouve (statut nul et PaymentIntent posée sur une demande close) et l'annule.
        assertThat(cashJob.run().settled()).isZero();
        jdbc.update("UPDATE bid_supplements SET updated_at = updated_at - interval '1 hour' WHERE id = ?", id);
        assertThat(cashJob.run().settled()).isEqualTo(1);
        assertThat(column(id, "commission_status")).isEqualTo("FAILED");
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID bidId) {
    }

    private Parties parties(String commissionCard) {
        return parties(commissionCard, "EUR");
    }

    private Parties parties(String commissionCard, String currency) {
        UserEntity traveler = persistUser(commissionCard);
        UserEntity sender = persistUser(null);
        UUID announcementId = persistAnnouncement(traveler.getId(), currency);
        UUID bidId = persistCashBid(announcementId, sender.getId(), currency);
        return new Parties(traveler, sender, bidId);
    }

    private static CreateSupplementRequest request(Parties p, CommissionSource source) {
        return new CreateSupplementRequest(new BigDecimal("20"), BidSupplementReason.OTHER, null, null,
                "tracking/" + p.bidId + "/1_supplement.jpg", source, null);
    }

    private void topUp(UUID userId, String amount) {
        topUp(userId, "EUR", amount);
    }

    private void topUp(UUID userId, String currency, String amount) {
        tx.executeWithoutResult(status -> walletService.credit(userId, currency, new BigDecimal(amount),
                WalletTransactionType.TOP_UP, "pi_topup_" + UUID.randomUUID(), "topup-" + UUID.randomUUID()));
    }

    /** Commission principale du colis prélevée sur le solde, comme l'acceptation espèces. */
    private void chargePrincipalCommissionFromWallet(Parties p, String amount) {
        tx.executeWithoutResult(status -> {
            walletService.debit(p.traveler.getId(), "EUR", new BigDecimal(amount),
                    WalletTransactionType.COMMISSION_DEDUCTED, p.bidId);
            BidEntity bid = bidRepository.findById(p.bidId).orElseThrow();
            bid.setCommissionStatus(CommissionStatus.CHARGED);
            bid.setCommissionChargedVia(CommissionChargedVia.WALLET);
            bid.setCommissionPaymentIntentId(null);
            bidRepository.save(bid);
        });
    }

    private String principalPi(Parties p) {
        return jdbc.queryForObject("SELECT commission_payment_intent_id FROM bids WHERE id = ?", String.class, p.bidId);
    }

    private static String event(String id, String type, String objectJson) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"" + com.stripe.Stripe.API_VERSION
                + "\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson + "}}";
    }

    private String column(UUID supplementId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM bid_supplements WHERE id = ?", String.class,
                supplementId);
    }

    private BigDecimal sum(String sql, Object arg) {
        return jdbc.queryForObject(sql, BigDecimal.class, arg);
    }

    private void awaitColumn(UUID supplementId, String column, String expected) throws InterruptedException {
        Supplier<String> value = () -> column(supplementId, column);
        for (int i = 0; i < 100 && !expected.equals(value.get()); i++) {
            Thread.sleep(100);
        }
        assertThat(value.get()).isEqualTo(expected);
    }

    private UserEntity persistUser(String commissionCard) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("supplement-cash-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        user.setStripeCustomerId("cus_" + UUID.randomUUID());
        user.setCommissionPaymentMethodId(commissionCard);
        return userRepository.saveAndFlush(user);
    }

    private UUID persistAnnouncement(UUID travelerId, String currency) {
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
            announcement.setCurrency(currency);
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    private UUID persistCashBid(UUID announcementId, UUID senderId, String currency) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCurrency(currency);
            bid.setWeightKg(new BigDecimal("2"));
            bid.setStatus(BidStatus.ACCEPTED);
            // Commission principale déjà prélevée par carte (acceptation espèces) : cohérente pour INV-10.
            bid.setCommissionStatus(CommissionStatus.CHARGED);
            bid.setCommissionChargedVia(CommissionChargedVia.CARD);
            bid.setCommissionPaymentIntentId("pi_principal_" + UUID.randomUUID());
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
