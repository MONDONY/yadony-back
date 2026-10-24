package com.yadony.api.payments.amendment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.param.PaymentIntentCreateParams;
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
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundedEvent;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.integrity.MoneyIntegrityMonitor;
import com.yadony.api.payments.supplement.StripeSupplementGateway;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rail espèces d'une modification de colis de bout en bout, sur un vrai PostgreSQL (V347 : contraintes du rail
 * espèces, type du grand livre, index uniques), Stripe simulé. La commission est prélevée AU VOYAGEUR quand il
 * accepte :
 * <ul>
 *   <li>solde : ligne {@code AMENDMENT_COMMISSION_DEDUCTED}, {@code bids.commission_*} intacts, puis commission
 *       rendue en {@code REFUND amendment-refund-<id>} sans faux positif d'intégrité (INV-09) ;</li>
 *   <li>solde court : 422 avec les mêmes propriétés que le supplément, la modification reste {@code PROPOSED} ;</li>
 *   <li>carte commission : {@code COMMISSION_PENDING} puis 3DS, webhook routé vers le rail espèces (jamais vers
 *       la commission du colis), échéance, commission tardive.</li>
 * </ul>
 */
@SpringBootTest(properties = "yadony.bid-amendment.enabled=true")
@ActiveProfiles("e2e")
@RecordApplicationEvents
@DisplayName("BidAmendmentCashRail : commission du voyageur à l'acceptation (PostgreSQL)")
class BidAmendmentCashRailIT {

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

    @Autowired BidAmendmentService service;
    @Autowired BidAmendmentCashRail cashRail;
    @Autowired BidAmendmentCashRefunds refunds;
    @Autowired StripeEventDispatcher dispatcher;
    @Autowired WalletService walletService;
    @Autowired MoneyIntegrityMonitor monitor;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    // ── Solde ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("accept_walletFunded_debitsAndAccepts : ligne AMENDMENT_COMMISSION_DEDUCTED, ACCEPTED, colis intact")
    void accept_walletFunded_debitsAndAccepts() {
        Parties p = parties(null, "EUR", "6.00");
        topUp(p.traveler.getId(), "EUR", "10.00");
        UUID id = create(p, "3");

        AmendmentAcceptResponse accepted = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null));

        // 3 kg × 10 € = 30 € net, commission figée 12 % = 3,60 €.
        assertThat(accepted.amendment().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.amendment().commissionAmount()).isEqualByComparingTo("3.60");
        assertThat(accepted.amendment().commissionStatus()).isEqualTo("CHARGED");
        assertThat(accepted.commissionClientSecret()).isNull();
        Map<String, Object> line = jdbc.queryForMap("SELECT type, amount, bid_id, payment_ref, idempotency_key "
                + "FROM wallet_transactions WHERE payment_ref = ?", "amendment-" + id);
        assertThat(line).containsEntry("type", "AMENDMENT_COMMISSION_DEDUCTED")
                .containsEntry("bid_id", p.bidId)
                .containsEntry("idempotency_key", "amendment-commission-" + id);
        assertThat((BigDecimal) line.get("amount")).isEqualByComparingTo("-3.60");
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("6.40");
        assertThat(column(id, "commission_status")).isEqualTo("CHARGED");
        assertThat(column(id, "commission_charged_via")).isEqualTo("WALLET");
        assertThat(column(id, "accepted_at")).isNotNull();
        // La commission principale du colis n'a pas bougé.
        assertThat(jdbc.queryForMap("SELECT commission_status, commission_charged_via FROM bids WHERE id = ?",
                p.bidId)).containsEntry("commission_status", "CHARGED").containsEntry("commission_charged_via", "CARD");
        assertThat(auditActions(id)).containsExactly("BID_AMENDMENT_REQUESTED",
                "BID_AMENDMENT_COMMISSION_CHARGED_WALLET", "BID_AMENDMENT_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM audit_log WHERE entity_id = ? AND action = ?",
                String.class, id, "BID_AMENDMENT_COMMISSION_CHARGED_WALLET")).doesNotContain("3.6");
        assertNoMoneyAnomaly(id);
    }

    @Test
    @DisplayName("accept_walletShort_422Unfunded_staysProposed : mêmes propriétés que le supplément, rien débité")
    void accept_walletShort_422Unfunded_staysProposed() {
        Parties p = parties(null, "EUR", "6.00");
        topUp(p.traveler.getId(), "EUR", "0.10");
        UUID id = create(p, "3");

        Throwable thrown = catchThrowable(() -> service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.WALLET_FIRST, null)));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo("amendment-commission-unfunded");
            assertThat(e.getStatus().value()).isEqualTo(422);
            assertThat(e.getProperties()).containsKeys("alternatives", "shortfall", "availableBalance",
                    "requiredCommission", "hasCard", "currency", "bidCurrency");
            assertThat((BigDecimal) e.getProperties().get("shortfall")).isEqualByComparingTo("3.50");
        });
        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        assertThat(column(id, "commission_status")).isNull();
        assertThat(column(id, "accepted_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE payment_ref = ?",
                Integer.class, "amendment-" + id)).isZero();
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("0.10");
        // Toujours proposée : le voyageur peut recharger puis accepter, les kilos restent réservés.
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
        topUp(p.traveler.getId(), "EUR", "5.00");
        assertThat(service.accept(p.bidId, id, p.traveler.getFirebaseUid(), new AmendmentAcceptRequest(null, null))
                .amendment().status()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("refund_afterAccepted_recreditsWallet : REFUND amendment-refund-<id>, REFUNDED, INV-09 sans alerte")
    void refund_afterAccepted_recreditsWallet() {
        Parties p = parties(null, "EUR", "6.00");
        topUp(p.traveler.getId(), "EUR", "10.00");
        UUID id = create(p, "3");
        service.accept(p.bidId, id, p.traveler.getFirebaseUid(), new AmendmentAcceptRequest(null, null));

        assertThat(refunds.settle(id)).isTrue();
        assertThat(refunds.settle(id)).as("rejeu sans effet").isFalse();

        assertThat(column(id, "status")).isEqualTo("REFUNDED");
        assertThat(column(id, "commission_status")).isEqualTo("REFUNDED");
        assertThat(column(id, "refunded_at")).isNotNull();
        Map<String, Object> credit = jdbc.queryForMap("SELECT amount, payment_ref, idempotency_key, bid_id "
                + "FROM wallet_transactions WHERE type = 'REFUND' AND payment_ref = ?", "amendment-refund-" + id);
        assertThat((BigDecimal) credit.get("amount")).isEqualByComparingTo("3.60");
        assertThat(credit).containsEntry("idempotency_key", "amendment-refund-" + id)
                .containsEntry("bid_id", p.bidId);
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("10.00");
        assertThat(events.stream(BidAmendmentRefundedEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.commissionOnly()).isTrue();
            assertThat(e.recipientId()).isEqualTo(p.traveler.getId());
            assertThat(e.amount()).isEqualByComparingTo("3.60");
        });
        assertThat(auditActions(id)).endsWith("BID_AMENDMENT_REFUNDED");
        Map<String, Long> counts = monitor.runAll();
        assertThat(counts).containsEntry("INV-01", 0L).containsEntry("INV-02", 0L).containsEntry("INV-03", 0L)
                .containsEntry("INV-09", 0L).containsEntry("INV-10", 0L);
        assertNoMoneyAnomaly(id);
    }

    @Test
    @DisplayName("refus avant acceptation : rien n'a été prélevé, rien à rendre")
    void decline_beforeAccept_nothingToRefund() {
        Parties p = parties(null, "EUR", "6.00");
        UUID id = create(p, "3");

        service.decline(p.bidId, id, p.traveler.getFirebaseUid());

        assertThat(column(id, "status")).isEqualTo("DECLINED");
        assertThat(refunds.settle(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ?", Integer.class,
                p.bidId)).isZero();
    }

    @Test
    @DisplayName("multidevise : colis XOF, complément EUR, deux lignes débitées puis deux recréditées")
    void multiCurrency_xofBidEurComplement_twoLinesDebitedThenRefunded() {
        Parties p = parties(null, "XOF", "6.00");
        topUp(p.traveler.getId(), "XOF", "300");
        topUp(p.traveler.getId(), "EUR", "10.00");
        UUID id = create(p, "3");

        AmendmentAcceptResponse accepted = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.WALLET_FIRST, "EUR"));

        assertThat(accepted.amendment().status()).isEqualTo("ACCEPTED");
        List<Map<String, Object>> debits = jdbc.queryForList("SELECT currency, amount, source_currency, "
                + "idempotency_key FROM wallet_transactions WHERE payment_ref = ? ORDER BY currency",
                "amendment-" + id);
        assertThat(debits).hasSize(2);
        assertThat(debits.get(0)).containsEntry("currency", "EUR").containsEntry("source_currency", "XOF")
                .containsEntry("idempotency_key", "amendment-commission-" + id + "-active");
        assertThat(debits.get(1)).containsEntry("currency", "XOF")
                .containsEntry("idempotency_key", "amendment-commission-" + id);
        assertThat(walletService.getBalance(p.traveler.getId(), "XOF")).isEqualByComparingTo("0");

        assertThat(refunds.settle(id)).isTrue();

        List<Map<String, Object>> credits = jdbc.queryForList("SELECT currency, idempotency_key FROM "
                + "wallet_transactions WHERE type = 'REFUND' AND payment_ref = ? ORDER BY currency",
                "amendment-refund-" + id);
        assertThat(credits).extracting(r -> r.get("idempotency_key")).containsExactly(
                "amendment-refund-" + id + "-EUR", "amendment-refund-" + id + "-XOF");
        assertThat(walletService.getBalance(p.traveler.getId(), "XOF")).isEqualByComparingTo("300");
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("10.00");
        assertThat(monitor.runAll()).containsEntry("INV-09", 0L).containsEntry("INV-10", 0L);
    }

    @Test
    @DisplayName("commission nulle : acquise tout de suite, rien débité, REFUNDED sans remboursement à la fin")
    void zeroCommission_acceptsWithoutDebit_andSettlesToRefunded() {
        Parties p = parties(null, "EUR", "6.00");
        UUID id = create(p, "3");
        jdbc.update("UPDATE bid_amendments SET commission_amount = 0, gross_amount = net_amount WHERE id = ?", id);

        AmendmentAcceptResponse accepted = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null));

        assertThat(accepted.amendment().status()).isEqualTo("ACCEPTED");
        assertThat(column(id, "commission_status")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ?", Integer.class,
                p.bidId)).isZero();

        assertThat(refunds.settle(id)).isTrue();
        assertThat(column(id, "status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ?", Integer.class,
                p.bidId)).isZero();
        assertThat(events.stream(BidAmendmentRefundedEvent.class)).isEmpty();
    }

    // ── Carte commission ─────────────────────────────────────────────────────

    @Test
    @DisplayName("accept_card_returnsClientSecret_thenWebhookAccepts : COMMISSION_PENDING, 3DS, webhook routé")
    void accept_card_returnsClientSecret_thenWebhookAccepts() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String piId = pi();
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(piId));
        UUID id = create(p, "3");

        AmendmentAcceptResponse accepted = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null));

        assertThat(accepted.amendment().status()).isEqualTo("COMMISSION_PENDING");
        assertThat(accepted.amendment().blocksHandover()).isTrue();
        assertThat(column(id, "responded_at")).as("point de départ de commissionConfirmWindow").isNotNull();
        assertThat(column(id, "commission_charged_via")).isEqualTo("CARD");
        assertThat(availableKg(p.announcementId)).as("kilos gardés pendant la 3DS").isEqualByComparingTo("3.00");

        AmendmentAcceptResponse afterCommit = cashRail.afterAcceptCommit(accepted);

        assertThat(afterCommit.commissionClientSecret()).isEqualTo(piId + "_secret");
        assertThat(afterCommit.amendment().commissionClientSecret()).isEqualTo(piId + "_secret");
        assertThat(afterCommit.amendment().status()).isEqualTo("COMMISSION_PENDING");
        assertThat(column(id, "commission_status")).isEqualTo("REQUIRES_3DS");
        assertThat(column(id, "commission_payment_intent_id")).isEqualTo(piId);
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(gateway).createPaymentIntent(params.capture(), key.capture());
        assertThat(key.getValue()).startsWith("amendment_commission_" + id + "_");
        assertThat(params.getValue().getMetadata()).containsEntry("bid_amendment_id", id.toString())
                .containsEntry("commission_purpose", "bid_amendment_commission")
                .doesNotContainKeys("bid_id", "payment_id");
        assertThat(params.getValue().getAmount()).isEqualTo(360L);

        String succeeded = event("payment_intent.succeeded", "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\","
                + "\"status\":\"succeeded\",\"metadata\":{\"bid_amendment_id\":\"" + id + "\","
                + "\"commission_purpose\":\"bid_amendment_commission\",\"scope\":\"BID_AMENDMENT_COMMISSION\"}}");
        assertThat(dispatcher.dispatch(succeeded)).isTrue();
        assertThat(dispatcher.dispatch(succeeded)).as("rejeu").isTrue();

        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
        assertThat(column(id, "commission_status")).isEqualTo("CHARGED");
        assertThat(jdbc.queryForMap("SELECT commission_status, commission_payment_intent_id FROM bids WHERE id = ?",
                p.bidId)).as("le webhook de la commission du colis ne l'a pas prise pour la sienne")
                .containsEntry("commission_status", "CHARGED")
                .doesNotContainEntry("commission_payment_intent_id", piId);
        assertThat(auditActions(id)).containsExactly("BID_AMENDMENT_REQUESTED", "BID_AMENDMENT_COMMISSION_PENDING",
                "BID_AMENDMENT_COMMISSION_3DS_REQUIRED", "BID_AMENDMENT_COMMISSION_CHARGED_CARD",
                "BID_AMENDMENT_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ?", Integer.class,
                p.bidId)).as("aucun débit du solde pour une commission par carte").isZero();
    }

    @Test
    @DisplayName("confirmation après 3DS rejouée : une seule transition, puis remboursement Stripe de la commission")
    void confirmCommission_afterThreeDs_acceptsOnce_thenRefundsCard() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String piId = pi();
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(piId));
        UUID id = create(p, "3");
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));
        when(gateway.retrievePaymentIntent(piId)).thenReturn(new PaymentIntentView(piId, "succeeded", 360L, "eur",
                null, "ch_c", List.of("card")));

        assertThat(cashRail.confirmCommission(p.bidId, id, p.traveler.getFirebaseUid()).status())
                .isEqualTo("ACCEPTED");
        assertThat(cashRail.confirmCommission(p.bidId, id, p.traveler.getFirebaseUid()).status())
                .isEqualTo("ACCEPTED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, id, "BID_AMENDMENT_ACCEPTED")).isEqualTo(1);

        when(gateway.findRefund(piId, "bid_amendment_id", id.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), eq("amendment_commission_refund_" + piId))).thenReturn("re_c");
        assertThat(refunds.settle(id)).isTrue();

        assertThat(column(id, "status")).isEqualTo("REFUNDED");
        assertThat(column(id, "commission_status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ?", Integer.class,
                p.bidId)).isZero();
        // charge.refunded de ce Refund : la commission est déjà soldée, aucun effet ni alerte.
        String refundedCharge = event("charge.refunded", "{\"id\":\"ch_c\",\"object\":\"charge\","
                + "\"payment_intent\":\"" + piId + "\",\"amount\":360,\"amount_refunded\":360}");
        assertThat(dispatcher.dispatch(refundedCharge)).isTrue();
        verify(alerts, never()).raiseOnce(any(), any(), any());
    }

    @Test
    @DisplayName("commissionPending_timeout_cancels : CANCELLED, kilos rendus, PaymentIntent de commission annulée")
    void commissionPending_timeout_cancels() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String piId = pi();
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(piId));
        when(gateway.cancelPaymentIntent(piId)).thenReturn(new PaymentIntentView(piId, "canceled", 360L, "eur", null,
                null, List.of("card")));
        UUID id = create(p, "3");
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));

        assertThat(service.expire(id)).as("dans la fenêtre de confirmation").isFalse();
        jdbc.update("UPDATE bid_amendments SET responded_at = responded_at - interval '31 minutes' WHERE id = ?", id);

        assertThat(service.expire(id)).isTrue();

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("COMMISSION_NOT_CONFIRMED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        awaitColumn(id, "commission_status", "FAILED");
        verify(gateway).cancelPaymentIntent(piId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ?", Integer.class,
                p.bidId)).isZero();
    }

    @Test
    @DisplayName("commission tardive après l'échéance : webhook enregistré puis remboursée par Stripe")
    void lateCommission_afterTimeout_isRefundedOnce() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String piId = pi();
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(piId));
        // L'annulation échoue : la 3DS aboutit pendant que la modification est close.
        when(gateway.cancelPaymentIntent(piId)).thenThrow(new ApiConnectionException("timeout"));
        when(gateway.retrievePaymentIntent(piId)).thenThrow(new ApiConnectionException("timeout"));
        when(gateway.findRefund(eq(piId), eq("bid_amendment_id"), any())).thenReturn(Optional.empty());
        UUID id = create(p, "3");
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));
        jdbc.update("UPDATE bid_amendments SET responded_at = responded_at - interval '31 minutes' WHERE id = ?", id);
        assertThat(service.expire(id)).isTrue();
        when(gateway.createRefund(eq(piId), anyMap(), eq("amendment_commission_refund_" + piId))).thenReturn("re_late");

        assertThat(dispatcher.dispatch(event("payment_intent.succeeded", "{\"id\":\"" + piId
                + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\",\"metadata\":{\"bid_amendment_id\":\""
                + id + "\",\"commission_purpose\":\"bid_amendment_commission\"}}"))).isTrue();

        awaitColumn(id, "commission_status", "REFUNDED");
        assertThat(column(id, "status")).as("la modification reste close").isEqualTo("CANCELLED");
        assertThat(auditActions(id)).contains("BID_AMENDMENT_LATE_COMMISSION_REFUND",
                "BID_AMENDMENT_COMMISSION_REFUNDED");
        verify(gateway).createRefund(eq(piId), anyMap(), eq("amendment_commission_refund_" + piId));
    }

    @Test
    @DisplayName("carte refusée : 422, retour en PROPOSED (kilos gardés, rien débité), puis nouvel essai au solde réussi")
    void cardDeclined_revertsToProposed_thenWalletRetryAccepts() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        when(gateway.createPaymentIntent(any(), any())).thenThrow(new CardException("declined", null,
                "card_declined", null, null, null, null, null));
        UUID id = create(p, "3");
        AmendmentAcceptResponse accepted = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null));

        assertThat(catchThrowable(() -> cashRail.afterAcceptCommit(accepted)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-commission-card-declined"));

        assertThat(column(id, "status")).as("la demande reste ouverte jusqu'à l'échéance").isEqualTo("PROPOSED");
        assertThat(column(id, "commission_status")).isEqualTo("FAILED");
        assertThat(column(id, "responded_at")).isNull();
        assertThat(column(id, "closed_reason")).isNull();
        assertThat(availableKg(p.announcementId)).as("kilos toujours réservés").isEqualByComparingTo("3.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE bid_id = ?", Integer.class,
                p.bidId)).isZero();

        topUp(p.traveler.getId(), "EUR", "10.00");
        AmendmentAcceptResponse retry = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null));

        assertThat(retry.amendment().status()).isEqualTo("ACCEPTED");
        assertThat(column(id, "commission_status")).isEqualTo("CHARGED");
        assertThat(column(id, "commission_charged_via")).isEqualTo("WALLET");
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("6.40");
    }

    @Test
    @DisplayName("3DS échouée puis nouvel essai par carte : PaymentIntent neuve (clé d'idempotence neuve), acceptée")
    void threeDsFailed_thenSecondCardAttempt_usesFreshKey_andAccepts() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String firstPi = pi();
        String secondPi = pi();
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(firstPi))
                .thenReturn(requiresAction(secondPi));
        when(gateway.cancelPaymentIntent(firstPi)).thenReturn(new PaymentIntentView(firstPi, "canceled", 360L, "eur",
                null, null, List.of("card")));
        when(gateway.retrievePaymentIntent(firstPi)).thenReturn(new PaymentIntentView(firstPi, "canceled", 360L,
                "eur", null, null, List.of("card")));
        UUID id = create(p, "3");
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));

        // La 3DS échoue : webhook payment_failed de la première PaymentIntent.
        assertThat(dispatcher.dispatch(event("payment_intent.payment_failed", "{\"id\":\"" + firstPi
                + "\",\"object\":\"payment_intent\",\"status\":\"requires_payment_method\","
                + "\"last_payment_error\":{\"code\":\"authentication_failure\"},\"metadata\":{"
                + "\"bid_amendment_id\":\"" + id + "\",\"commission_purpose\":\"bid_amendment_commission\"}}")))
                .isTrue();

        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        assertThat(column(id, "commission_status")).isEqualTo("FAILED");
        assertThat(column(id, "responded_at")).isNull();
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
        verify(gateway).cancelPaymentIntent(firstPi);

        // Deuxième essai, même carte : nouvelle PaymentIntent.
        Thread.sleep(5);
        AmendmentAcceptResponse second = cashRail.afterAcceptCommit(service.accept(p.bidId, id,
                p.traveler.getFirebaseUid(), new AmendmentAcceptRequest(CommissionSource.CARD, null)));

        assertThat(second.commissionClientSecret()).isEqualTo(secondPi + "_secret");
        assertThat(column(id, "commission_payment_intent_id")).isEqualTo(secondPi);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.times(2)).createPaymentIntent(any(), keys.capture());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();

        // Un signal tardif de la première PaymentIntent n'affecte pas le second essai.
        assertThat(dispatcher.dispatch(event("payment_intent.canceled", "{\"id\":\"" + firstPi
                + "\",\"object\":\"payment_intent\",\"status\":\"canceled\",\"metadata\":{"
                + "\"bid_amendment_id\":\"" + id + "\",\"commission_purpose\":\"bid_amendment_commission\"}}")))
                .isTrue();
        assertThat(column(id, "status")).isEqualTo("COMMISSION_PENDING");

        assertThat(dispatcher.dispatch(event("payment_intent.succeeded", "{\"id\":\"" + secondPi
                + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\",\"metadata\":{"
                + "\"bid_amendment_id\":\"" + id + "\",\"commission_purpose\":\"bid_amendment_commission\"}}")))
                .isTrue();

        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
        assertThat(column(id, "commission_status")).isEqualTo("CHARGED");
        assertThat(column(id, "commission_payment_intent_id")).isEqualTo(secondPi);
    }

    // ── PaymentIntent d'un essai précédent qui aboutit tard ──────────────────

    private static String commissionEvent(String type, String piId, UUID amendmentId, String attempt) {
        return event(type, "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\",\"status\":\""
                + ("payment_intent.succeeded".equals(type) ? "succeeded" : "requires_payment_method")
                + "\",\"metadata\":{\"bid_amendment_id\":\"" + amendmentId
                + "\",\"commission_purpose\":\"bid_amendment_commission\""
                + (attempt == null ? "" : ",\"commission_attempt\":\"" + attempt + "\"") + "}}");
    }

    private static PaymentIntentView stripeView(String piId, String status) {
        return new PaymentIntentView(piId, status, 360L, "eur", piId + "_secret", null, List.of("card"));
    }

    /** Numéro d'essai porté par la dernière PaymentIntent créée. */
    private String lastAttempt() throws Exception {
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce()).createPaymentIntent(params.capture(), any());
        return params.getValue().getMetadata().get("commission_attempt");
    }

    /** Premier essai par carte (PI1 en 3DS) puis échec de la 3DS : la modification est de nouveau PROPOSED. */
    private String failedFirstCardAttempt(Parties p, UUID id, String firstPi) throws Exception {
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(firstPi));
        when(gateway.cancelPaymentIntent(firstPi)).thenReturn(stripeView(firstPi, "canceled"));
        when(gateway.retrievePaymentIntent(firstPi)).thenReturn(stripeView(firstPi, "canceled"));
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));
        String attempt = lastAttempt();
        assertThat(dispatcher.dispatch(commissionEvent("payment_intent.payment_failed", firstPi, id, attempt)))
                .isTrue();
        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        return attempt;
    }

    @Test
    @DisplayName("PI1 aboutit tard après un essai 2 réussi au solde : PI1 remboursée, une seule commission retenue")
    void latePaymentIntent_afterWalletRetry_isRefunded_singleCommissionKept() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String pi1 = pi();
        UUID id = create(p, "3");
        String attempt1 = failedFirstCardAttempt(p, id, pi1);
        topUp(p.traveler.getId(), "EUR", "10.00");
        service.accept(p.bidId, id, p.traveler.getFirebaseUid(), new AmendmentAcceptRequest(null, null));
        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
        assertThat(column(id, "commission_payment_intent_id")).as("la PaymentIntent de l'essai échoué est oubliée")
                .isNull();
        when(gateway.findRefund(eq(pi1), eq("bid_amendment_id"), any())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(pi1), anyMap(), eq("amendment_commission_refund_" + pi1))).thenReturn("re_1");

        assertThat(dispatcher.dispatch(commissionEvent("payment_intent.succeeded", pi1, id, attempt1))).isTrue();

        verify(gateway, org.mockito.Mockito.timeout(5000)).createRefund(eq(pi1), anyMap(),
                eq("amendment_commission_refund_" + pi1));
        // L'alerte est levée par le ledger, dans la transaction du webhook : présente sans attendre l'écouteur.
        verify(alerts).raiseOnce(eq("AMEND_STALE_COMM_" + pi1),
                org.mockito.ArgumentMatchers.anyString(), anyMap());
        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
        assertThat(column(id, "commission_charged_via")).isEqualTo("WALLET");
        assertThat(column(id, "commission_status")).isEqualTo("CHARGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE payment_ref = ?",
                Integer.class, "amendment-" + id)).isEqualTo(1);
        assertThat(walletService.getBalance(p.traveler.getId(), "EUR")).isEqualByComparingTo("6.40");
        assertThat(auditActions(id)).contains("BID_AMENDMENT_STALE_COMMISSION");
        assertThat(dispatcher.dispatch(commissionEvent("payment_intent.succeeded", pi1, id, attempt1)))
                .as("rejeu du webhook").isTrue();
    }

    @Test
    @DisplayName("PI1 aboutit tard pendant un essai 2 par carte (PI2) : PI1 remboursée, l'essai 2 continue puis l'emporte")
    void latePaymentIntent_duringSecondCardAttempt_isRefunded_secondAttemptStillAccepts() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String pi1 = pi();
        String pi2 = pi();
        UUID id = create(p, "3");
        String attempt1 = failedFirstCardAttempt(p, id, pi1);
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(pi2));
        Thread.sleep(5);
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));
        String attempt2 = lastAttempt();
        assertThat(attempt2).isNotEqualTo(attempt1);
        assertThat(column(id, "commission_payment_intent_id")).isEqualTo(pi2);
        when(gateway.findRefund(eq(pi1), eq("bid_amendment_id"), any())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(pi1), anyMap(), eq("amendment_commission_refund_" + pi1))).thenReturn("re_1");

        assertThat(dispatcher.dispatch(commissionEvent("payment_intent.succeeded", pi1, id, attempt1))).isTrue();

        verify(gateway, org.mockito.Mockito.timeout(5000)).createRefund(eq(pi1), anyMap(),
                eq("amendment_commission_refund_" + pi1));
        assertThat(column(id, "status")).isEqualTo("COMMISSION_PENDING");
        assertThat(column(id, "commission_payment_intent_id")).isEqualTo(pi2);

        assertThat(dispatcher.dispatch(commissionEvent("payment_intent.succeeded", pi2, id, attempt2))).isTrue();

        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
        assertThat(column(id, "commission_payment_intent_id")).isEqualTo(pi2);
        verify(gateway, never()).createRefund(eq(pi2), anyMap(), any());
    }

    @Test
    @DisplayName("nouvel essai alors que PI1 est processing : 409 amendment-commission-in-progress, rien de créé")
    void retryWhilePreviousPaymentIntentProcessing_409() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String pi1 = pi();
        UUID id = create(p, "3");
        failedFirstCardAttempt(p, id, pi1);
        when(gateway.retrievePaymentIntent(pi1)).thenReturn(stripeView(pi1, "processing"));

        AmendmentAcceptResponse retry = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null));
        assertThat(retry.amendment().status()).isEqualTo("COMMISSION_PENDING");

        assertThat(catchThrowable(() -> cashRail.afterAcceptCommit(retry))).isInstanceOfSatisfying(
                YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("amendment-commission-in-progress");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });

        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        assertThat(column(id, "commission_status")).isEqualTo("FAILED");
        assertThat(column(id, "responded_at")).isNull();
        verify(gateway, org.mockito.Mockito.times(1)).createPaymentIntent(any(), any());
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("commission réussie après l'échéance de confirmation : modification close, jamais acceptée, commission remboursée")
    void commissionSucceedsAfterConfirmationWindow_closedAndRefunded() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String piId = pi();
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(piId));
        UUID id = create(p, "3");
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));
        String attempt = lastAttempt();
        jdbc.update("UPDATE bid_amendments SET responded_at = responded_at - interval '31 minutes' WHERE id = ?", id);
        when(gateway.findRefund(eq(piId), eq("bid_amendment_id"), any())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), eq("amendment_commission_refund_" + piId))).thenReturn("re_1");

        assertThat(dispatcher.dispatch(commissionEvent("payment_intent.succeeded", piId, id, attempt))).isTrue();

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("COMMISSION_NOT_CONFIRMED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(column(id, "accepted_at")).isNull();
        awaitColumn(id, "commission_status", "REFUNDED");
        verify(gateway).createRefund(eq(piId), anyMap(), eq("amendment_commission_refund_" + piId));
    }

    @Test
    @DisplayName("commission réussie alors que le colis n'est plus accepté (remis) : modification close (BID_ENDED), commission remboursée")
    void commissionSucceedsAfterBidEnded_closedAndRefunded() throws Exception {
        Parties p = parties("pm_card", "EUR", "6.00");
        String piId = pi();
        when(gateway.createPaymentIntent(any(), any())).thenReturn(requiresAction(piId));
        UUID id = create(p, "3");
        cashRail.afterAcceptCommit(service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null)));
        String attempt = lastAttempt();
        jdbc.update("UPDATE bids SET status = 'HANDED_OVER' WHERE id = ?", p.bidId);
        when(gateway.findRefund(eq(piId), eq("bid_amendment_id"), any())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), eq("amendment_commission_refund_" + piId))).thenReturn("re_1");

        assertThat(dispatcher.dispatch(commissionEvent("payment_intent.succeeded", piId, id, attempt))).isTrue();

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("BID_ENDED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        awaitColumn(id, "commission_status", "REFUNDED");
    }

    @Test
    @DisplayName("sans carte commission : 422 à l'acceptation, la modification reste PROPOSED")
    void accept_cardWithoutCommissionCard_staysProposed() {
        Parties p = parties(null, "EUR", "6.00");
        UUID id = create(p, "3");

        assertThat(catchThrowable(() -> service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.CARD, null))))
                .isInstanceOf(com.yadony.api.payments.cash.exception.CommissionMethodMissingException.class);

        assertThat(column(id, "status")).isEqualTo("PROPOSED");
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID announcementId, UUID bidId) {
    }

    private Parties parties(String commissionCard, String currency, String availableKg) {
        UserEntity traveler = persistUser(commissionCard);
        UserEntity sender = persistUser(null);
        UUID announcementId = persistAnnouncement(traveler.getId(), currency, availableKg);
        UUID bidId = persistCashBid(announcementId, sender.getId(), currency);
        return new Parties(traveler, sender, announcementId, bidId);
    }

    private UUID create(Parties p, String kg) {
        return service.create(p.bidId, p.sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal(kg), null, PaymentMethod.CASH)).amendment().id();
    }

    private static String pi() {
        return "pi_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static PaymentIntentView requiresAction(String piId) {
        return new PaymentIntentView(piId, "requires_action", 360L, "eur", piId + "_secret", null, List.of("card"));
    }

    private void topUp(UUID userId, String currency, String amount) {
        tx.executeWithoutResult(status -> walletService.credit(userId, currency, new BigDecimal(amount),
                WalletTransactionType.TOP_UP, "pi_topup_" + UUID.randomUUID(), "topup-" + UUID.randomUUID()));
    }

    private static String event(String type, String objectJson) {
        return "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"object\":\"event\",\"api_version\":\""
                + com.stripe.Stripe.API_VERSION + "\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson
                + "}}";
    }

    private String column(UUID id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM bid_amendments WHERE id = ?", String.class, id);
    }

    private BigDecimal availableKg(UUID announcementId) {
        return jdbc.queryForObject("SELECT available_kg FROM announcements WHERE id = ?", BigDecimal.class,
                announcementId);
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

    /** Sonde d'intégrité : aucune ligne en faute pour cette modification (INV-09). */
    private void assertNoMoneyAnomaly(UUID amendmentId) {
        String id = amendmentId.toString();
        assertThat(monitor.inspect("INV-09", MoneyIntegrityMonitor.MAX_INSPECT_ROWS).orElseThrow().rows())
                .as("INV-09 pour la modification " + id)
                .noneMatch(r -> r.values().stream().anyMatch(v -> String.valueOf(v).contains(id)));
    }

    private UserEntity persistUser(String commissionCard) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("amendment-cash-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        user.setStripeCustomerId("cus_" + UUID.randomUUID());
        user.setCommissionPaymentMethodId(commissionCard);
        return userRepository.saveAndFlush(user);
    }

    /** Trajet espèces ; 10 €/kg (6 000 XOF/kg en XOF). */
    private UUID persistAnnouncement(UUID travelerId, String currency, String availableKg) {
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
            announcement.setTotalKg(new BigDecimal("60.00"));
            announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            announcement.setPricePerKg("XOF".equals(currency) ? new BigDecimal("6000") : new BigDecimal("10.00"));
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
            bid.setCommissionRate(new BigDecimal("0.12"));
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
