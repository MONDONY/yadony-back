package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentReminderDueEvent;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.StripeSupplementGateway;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.tracking.TrackingEventType;
import com.yadony.api.tracking.TrackingService;
import com.yadony.api.tracking.dto.QrScanRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fin de vie d'une modification de colis, sur un vrai PostgreSQL : portail de la remise (scan DEPART), fin du colis
 * (écouteur des huit événements de fin), « les kilos en plus ne sont pas là » et filet planifié (expirations,
 * rappel, colis terminés oubliés, commissions espèces à rendre, remboursements hors essai, alerte « non soldée »).
 *
 * <p>Les rails carte (B9) et espèces (B5) sont les vrais ; Stripe est simulé. L'autorisation carte est rendue après
 * le commit de la clôture ({@link StripeAmendmentGateway#cancelPaymentIntent}).
 */
@SpringBootTest(properties = "yadony.bid-amendment.enabled=true")
@ActiveProfiles("e2e")
@RecordApplicationEvents
@DisplayName("BidAmendment : remise, fin de colis, non apportée, filet (PostgreSQL)")
class BidAmendmentLifecycleIT {

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
    @MockitoBean StripeAmendmentGateway cardGateway;
    @MockitoBean AdminAlertEscalator alerts;
    @MockitoSpyBean BidAmendmentCashRefunds cashRefunds;

    @Autowired BidAmendmentService service;
    @Autowired BidAmendmentCardRail cardRail;
    @Autowired BidAmendmentProperties properties;
    @Autowired BidAmendmentReconciliationJob job;
    @Autowired TrackingService trackingService;
    @Autowired WalletService walletService;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    // ── Portail de la remise ────────────────────────────────────────────────

    @Test
    @DisplayName("handoverGate_openProposal_blocksWith409Code : scan DEPART refusé en 409 handover-amendment-pending")
    void handoverGate_openProposal_blocksWith409Code() {
        Parties p = parties(false);
        UUID id = create(p, "3", PaymentMethod.CASH);

        Throwable refused = catchThrowable(() -> trackingService.recordScan(depart(p.bidId), p.traveler.getFirebaseUid()));

        assertThat(refused).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(BidAmendmentHandoverGate.CODE).isEqualTo("handover-amendment-pending");
            assertThat(e.getStatus().value()).isEqualTo(409);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking_events WHERE bid_id = ?", Integer.class, p.bidId))
                .isZero();
        assertThat(bidStatus(p.bidId)).isEqualTo("ACCEPTED");
        assertThat(column(id, "status")).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("handoverGate_expiredProposal_unblocksAndExpires : demande échue, la remise passe et la clôt sous le verrou")
    void handoverGate_expiredProposal_unblocksAndExpires() {
        Parties p = parties(false);
        UUID id = create(p, "3", PaymentMethod.CASH);
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
        lapse(id);

        assertThat(trackingService.recordScan(depart(p.bidId), p.traveler.getFirebaseUid()).created()).isTrue();

        assertThat(column(id, "status")).isEqualTo("EXPIRED");
        assertThat(column(id, "closed_reason")).isEqualTo("EXPIRED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(bidStatus(p.bidId)).isEqualTo("HANDED_OVER");
    }

    // ── Fin du colis ────────────────────────────────────────────────────────

    @Test
    @DisplayName("onBidEnded_releasesCapacityOnceAndCancelsAuthorization : deux événements de fin, un seul effet")
    void onBidEnded_releasesCapacityOnceAndCancelsAuthorization() throws Exception {
        Parties p = parties(true);
        UUID id = create(p, "3", PaymentMethod.STRIPE);
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("UPDATE bid_amendments SET stripe_payment_intent_id = ? WHERE id = ?", piId, id);
        when(cardGateway.cancelPaymentIntent(piId)).thenReturn(new StripeSupplementGateway.PaymentIntentView(piId,
                "canceled", 3360L, "eur", null, null, List.of("card")));
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
        jdbc.update("UPDATE bids SET status = 'CANCELLED' WHERE id = ?", p.bidId);

        UUID cancellationId = UUID.randomUUID();
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new CancellationConfirmedEvent(p.bidId, cancellationId, CancellationReason.OTHER)));
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new CancellationConfirmedEvent(p.bidId, cancellationId, CancellationReason.OTHER)));

        awaitColumn(id, "status", "CANCELLED");
        verify(cardGateway, after(1500).times(1)).cancelPaymentIntent(piId);
        assertThat(column(id, "closed_reason")).isEqualTo("BID_ENDED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(reservedKg(id)).isEqualByComparingTo("0");
        assertThat(auditActions(id)).containsOnlyOnce("BID_AMENDMENT_CANCELLED");
    }

    @Test
    @DisplayName("onBidEnded_acceptedCash_refundsCommission : kilos rendus, REFUNDED, commission recréditée une fois")
    void onBidEnded_acceptedCash_refundsCommission() throws Exception {
        Parties p = parties(false);
        topUp(p.traveler.getId(), "10.00");
        UUID id = create(p, "3", PaymentMethod.CASH);
        acceptWithWallet(p, id);
        assertThat(column(id, "commission_status")).isEqualTo("CHARGED");
        jdbc.update("UPDATE bids SET status = 'CANCELLED' WHERE id = ?", p.bidId);

        tx.executeWithoutResult(s -> publisher.publishEvent(
                new CancellationConfirmedEvent(p.bidId, UUID.randomUUID(), CancellationReason.OTHER)));

        awaitColumn(id, "commission_status", "REFUNDED");
        assertThat(column(id, "status")).isEqualTo("REFUNDED");
        assertThat(column(id, "closed_reason")).isEqualTo("BID_ENDED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(refundCredits(p.traveler.getId(), id)).isEqualTo(1);
        verify(cashRefunds, after(1000).times(1)).settle(id);
    }

    @Test
    @DisplayName("onBidEnded_acceptedCard_refundPending : carte acquise → REFUND_PENDING, remboursement demandé au rail")
    void onBidEnded_acceptedCard_refundPending() throws Exception {
        Parties p = parties(true);
        UUID id = create(p, "3", PaymentMethod.STRIPE);
        acceptCard(p, id);
        jdbc.update("UPDATE bids SET status = 'REJECTED' WHERE id = ?", p.bidId);

        assertThat(service.onBidEnded(p.bidId, "BID_REJECTED")).isTrue();
        assertThat(service.onBidEnded(p.bidId, "BID_REJECTED")).as("idempotent").isFalse();

        assertThat(column(id, "status")).isEqualTo("REFUND_PENDING");
        assertThat(column(id, "closed_reason")).isEqualTo("BID_ENDED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(events.stream(BidAmendmentRefundRequestedEvent.class)
                .filter(e -> e.amendmentId().equals(id))).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("BID_REJECTED"));
        verify(cardGateway, after(500).never()).cancelPaymentIntent(anyString());
        assertThat(auditActions(id)).contains("BID_AMENDMENT_REFUND_REQUESTED");
    }

    @Test
    @DisplayName("onBidEnded : colis terminé sans modification à traiter, sortie rapide sans rien toucher")
    void onBidEnded_nothingToEnd_returnsFalse() {
        Parties p = parties(false);
        UUID id = create(p, "3", PaymentMethod.CASH);
        service.cancel(p.bidId, id, p.sender.getFirebaseUid());
        jdbc.update("UPDATE bids SET status = 'CANCELLED' WHERE id = ?", p.bidId);

        assertThat(service.onBidEnded(p.bidId, "CANCELLATION_CONFIRMED")).isFalse();
        assertThat(service.onPrincipalRefunded(p.bidId)).isFalse();

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("onBidEnded : colis encore accepté, rien n'est touché")
    void onBidEnded_bidNotEnded_noop() {
        Parties p = parties(false);
        UUID id = create(p, "3", PaymentMethod.CASH);

        assertThat(service.onBidEnded(p.bidId, "CANCELLATION_CONFIRMED")).isFalse();

        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("onPrincipalRefunded : principal remboursé par l'admin, colis en cours → la modification carte suit")
    void onPrincipalRefunded_liveBid_refundsAmendmentToo() throws Exception {
        Parties p = parties(true);
        UUID id = create(p, "3", PaymentMethod.STRIPE);
        acceptCard(p, id);

        assertThat(service.onPrincipalRefunded(p.bidId)).isTrue();

        assertThat(column(id, "status")).isEqualTo("REFUND_PENDING");
        assertThat(events.stream(BidAmendmentRefundRequestedEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("ADMIN_REFUND"));
    }

    // ── Modification non apportée ───────────────────────────────────────────

    @Test
    @DisplayName("notBrought_acceptedCash_refundsCommissionAndReleases : kilos rendus, commission rendue, événement et audit")
    void notBrought_acceptedCash_refundsCommissionAndReleases() throws Exception {
        Parties p = parties(false);
        topUp(p.traveler.getId(), "10.00");
        UUID id = create(p, "3", PaymentMethod.CASH);
        acceptWithWallet(p, id);
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");

        BidAmendmentResponse view = service.notBrought(p.bidId, id, p.traveler.getFirebaseUid());

        assertThat(view.status()).isEqualTo("REFUNDED");
        assertThat(view.closedReason()).isEqualTo("NOT_BROUGHT");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(reservedKg(id)).isEqualByComparingTo("0");
        assertThat(bidStatus(p.bidId)).isEqualTo("ACCEPTED");
        assertThat(events.stream(BidAmendmentClosedEvent.NotBrought.class)).singleElement().satisfies(e -> {
            assertThat(e.amendmentId()).isEqualTo(id);
            assertThat(e.previousStatus()).isEqualTo(BidAmendmentStatus.ACCEPTED);
            assertThat(e.closedReason()).isEqualTo(BidAmendmentClosedReason.NOT_BROUGHT);
        });
        // Espèces : le remboursement part de l'événement de clôture, jamais aussi d'une demande de remboursement.
        assertThat(events.stream(BidAmendmentRefundRequestedEvent.class)).isEmpty();
        awaitColumn(id, "commission_status", "REFUNDED");
        assertThat(refundCredits(p.traveler.getId(), id)).isEqualTo(1);
        verify(cashRefunds, after(1000).times(1)).settle(id);
        assertThat(auditActions(id)).containsOnlyOnce("BID_AMENDMENT_NOT_BROUGHT");

        // Second geste : la modification n'est plus acceptée.
        assertThat(catchThrowable(() -> service.notBrought(p.bidId, id, p.traveler.getFirebaseUid())))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-accepted"));
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("notBrought sur une modification carte : REFUND_PENDING et remboursement demandé")
    void notBrought_acceptedCard_requestsRefund() throws Exception {
        Parties p = parties(true);
        UUID id = create(p, "3", PaymentMethod.STRIPE);
        acceptCard(p, id);

        BidAmendmentResponse view = service.notBrought(p.bidId, id, p.traveler.getFirebaseUid());

        assertThat(view.status()).isEqualTo("REFUND_PENDING");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(events.stream(BidAmendmentRefundRequestedEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("NOT_BROUGHT"));
        assertThat(events.stream(BidAmendmentClosedEvent.NotBrought.class)).hasSize(1);
    }

    @Test
    @DisplayName("notBrought drapeau éteint : rendre l'argent reste possible")
    void notBrought_flagDisabled_stillWorks() throws Exception {
        Parties p = parties(false);
        topUp(p.traveler.getId(), "10.00");
        UUID id = create(p, "3", PaymentMethod.CASH);
        acceptWithWallet(p, id);
        properties.setEnabled(false);
        try {
            BidAmendmentResponse view = service.notBrought(p.bidId, id, p.traveler.getFirebaseUid());

            assertThat(view.status()).isEqualTo("REFUNDED");
            assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
            awaitColumn(id, "commission_status", "REFUNDED");
        } finally {
            properties.setEnabled(true);
        }
    }

    @Test
    @DisplayName("notBrought_afterHandover_422InvalidStatus : colis remis, rien ne change")
    void notBrought_afterHandover_422InvalidStatus() {
        Parties p = parties(false);
        topUp(p.traveler.getId(), "10.00");
        UUID id = create(p, "3", PaymentMethod.CASH);
        acceptWithWallet(p, id);
        jdbc.update("UPDATE bids SET status = 'HANDED_OVER' WHERE id = ?", p.bidId);

        assertThat(catchThrowable(() -> service.notBrought(p.bidId, id, p.traveler.getFirebaseUid())))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("invalid-bid-status");
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("notBrought par l'expéditeur : 403")
    void notBrought_bySender_403() {
        Parties p = parties(false);
        topUp(p.traveler.getId(), "10.00");
        UUID id = create(p, "3", PaymentMethod.CASH);
        acceptWithWallet(p, id);

        assertThat(catchThrowable(() -> service.notBrought(p.bidId, id, p.sender.getFirebaseUid())))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
    }

    // ── Filet planifié ──────────────────────────────────────────────────────

    @Test
    @DisplayName("reminder_sentOnce : rappel au voyageur une seule fois, à reminderBefore de l'échéance, jamais pour une fenêtre courte")
    void reminder_sentOnce() {
        Parties p = parties(false);
        UUID id = create(p, "3", PaymentMethod.CASH);
        UUID farId = create(parties(false), "3", PaymentMethod.CASH);
        // Fenêtre de 48 h dont il reste 1 h : rappel dû.
        jdbc.update("UPDATE bid_amendments SET created_at = created_at - interval '47 hours', "
                + "expires_at = created_at + interval '1 hour' WHERE id = ?", id);
        // Fenêtre d'1 h seulement (départ proche) : jamais de rappel, la notification de demande vient de partir.
        UUID shortId = create(parties(false), "3", PaymentMethod.CASH);
        jdbc.update("UPDATE bid_amendments SET expires_at = created_at + interval '1 hour' WHERE id = ?", shortId);

        job.run();
        job.run();

        List<BidAmendmentReminderDueEvent> reminders = events.stream(BidAmendmentReminderDueEvent.class).toList();
        assertThat(reminders).singleElement().satisfies(e -> {
            assertThat(e.amendmentId()).isEqualTo(id);
            assertThat(e.bidId()).isEqualTo(p.bidId);
            assertThat(e.travelerId()).isEqualTo(p.traveler.getId());
        });
        assertThat(column(id, "reminder_sent_at")).isNotNull();
        assertThat(column(farId, "reminder_sent_at")).as("échéance encore lointaine (48 h)").isNull();
        assertThat(column(shortId, "reminder_sent_at")).as("fenêtre plus courte que reminderBefore").isNull();
        assertThat(service.remindTraveler(shortId)).isFalse();
        assertThat(column(id, "status")).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("filet : autorisation carte jamais confirmée au-delà de authorizationWindow → CANCELLED, kilos rendus")
    void job_expiresLapsedAuthorization() {
        Parties p = parties(true);
        UUID id = create(p, "3", PaymentMethod.STRIPE);
        jdbc.update("UPDATE bid_amendments SET status = 'AUTHORIZATION_PENDING', "
                + "created_at = created_at - interval '31 minutes' WHERE id = ?", id);

        job.run();

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("AUTHORIZATION_NOT_CONFIRMED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("filet : colis terminé dont l'événement de fin a été perdu → modification close (grâce de 15 min)")
    void job_closesForgottenEndedBid() {
        Parties recent = parties(false);
        UUID recentId = create(recent, "3", PaymentMethod.CASH);
        jdbc.update("UPDATE bids SET status = 'NO_SHOW' WHERE id = ?", recent.bidId);
        Parties old = parties(false);
        UUID oldId = create(old, "3", PaymentMethod.CASH);
        jdbc.update("UPDATE bids SET status = 'NO_SHOW', updated_at = updated_at - interval '1 hour' WHERE id = ?",
                old.bidId);

        job.run();

        assertThat(column(oldId, "status")).isEqualTo("CANCELLED");
        assertThat(column(oldId, "closed_reason")).isEqualTo("BID_ENDED");
        assertThat(availableKg(old.announcementId)).isEqualByComparingTo("6.00");
        assertThat(column(recentId, "status")).as("laissé à son événement de fin").isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("filet : commission espèces restée prélevée sur une modification close → rendue")
    void job_retriesCashCommissionRefund() throws Exception {
        Parties p = parties(false);
        topUp(p.traveler.getId(), "10.00");
        UUID id = create(p, "3", PaymentMethod.CASH);
        acceptWithWallet(p, id);
        // Clôture dont la suite (remboursement après commit) a été perdue.
        jdbc.update("UPDATE bid_amendments SET status = 'REFUNDED', capacity_reserved_kg = 0, "
                + "updated_at = updated_at - interval '1 hour' WHERE id = ?", id);

        job.run();

        assertThat(column(id, "commission_status")).isEqualTo("REFUNDED");
        assertThat(refundCredits(p.traveler.getId(), id)).isEqualTo(1);
        job.run();
        assertThat(refundCredits(p.traveler.getId(), id)).isEqualTo(1);
    }

    @Test
    @DisplayName("filet : PaymentIntent de commission hors essai non rendue → Refund rejoué une fois par PaymentIntent")
    void job_replaysStaleCommissionRefunds() throws Exception {
        Parties p = parties(false);
        UUID id = create(p, "3", PaymentMethod.CASH);
        String lost = "pi_lost_" + UUID.randomUUID().toString().substring(0, 8);
        String confirmed = "pi_conf_" + UUID.randomUUID().toString().substring(0, 8);
        String tooOld = "pi_old_" + UUID.randomUUID().toString().substring(0, 8);
        // Un audit par rejeu de webhook : deux pour la même PaymentIntent.
        insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION", lost, 0);
        insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION", lost, 0);
        insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION", confirmed, 0);
        insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION_REFUND_CONFIRMED", confirmed, 0);
        insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION", tooOld, 40 * 24);
        // Trois Refunds échoués étalés sur plus de 24 h : sortie du balayage (l'alerte d'échec reste).
        String failing = "pi_fail_" + UUID.randomUUID().toString().substring(0, 8);
        insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION", failing, 36);
        // Marges de plusieurs heures : les horodatages SQL (UTC) et ceux liés par Hibernate peuvent différer du
        // décalage de la JVM (voir lapse()).
        for (int hoursAgo : new int[]{30, 12, 1}) {
            insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION_REFUND_FAILED", failing, hoursAgo);
        }
        // Trois échecs rapprochés (panne Stripe d'une demi-heure) : toujours rejouée.
        String outage = "pi_outage_" + UUID.randomUUID().toString().substring(0, 8);
        insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION", outage, 1);
        for (int i = 0; i < 3; i++) {
            insertAudit(id, p.bidId, "BID_AMENDMENT_STALE_COMMISSION_REFUND_FAILED", outage, 0);
        }
        when(gateway.findRefund(eq(outage), eq("bid_amendment_id"), eq(id.toString()))).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(outage), anyMap(), eq("amendment_commission_refund_" + outage)))
                .thenReturn("re_outage");
        when(gateway.findRefund(eq(lost), eq("bid_amendment_id"), eq(id.toString()))).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(lost), anyMap(), eq("amendment_commission_refund_" + lost))).thenReturn("re_lost");

        job.run();
        job.run();

        verify(gateway, times(1)).createRefund(eq(lost), anyMap(), eq("amendment_commission_refund_" + lost));
        verify(gateway, never()).findRefund(eq(confirmed), any(), any());
        verify(gateway, never()).findRefund(eq(tooOld), any(), any());
        verify(gateway, never()).findRefund(eq(failing), any(), any());
        verify(gateway, times(1)).createRefund(eq(outage), anyMap(), eq("amendment_commission_refund_" + outage));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? "
                + "AND action = 'BID_AMENDMENT_STALE_COMMISSION_REFUNDED'", Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("filet : modification acquise ni versée ni remboursée 3 jours après la fin du colis → alerte AMENDMENT_UNSETTLED_")
    void job_raisesUnsettledAlert() throws Exception {
        Parties p = parties(true);
        UUID id = create(p, "3", PaymentMethod.STRIPE);
        acceptCard(p, id);
        jdbc.update("UPDATE bids SET status = 'COMPLETED', updated_at = updated_at - interval '4 days' WHERE id = ?",
                p.bidId);
        Parties recent = parties(true);
        UUID recentId = create(recent, "3", PaymentMethod.STRIPE);
        acceptCard(recent, recentId);
        jdbc.update("UPDATE bids SET status = 'COMPLETED' WHERE id = ?", recent.bidId);
        // Déjà signalée (alerte encore ouverte) : écartée du balayage, elle ne bloque pas les nouvelles.
        Parties alerted = parties(true);
        UUID alertedId = create(alerted, "3", PaymentMethod.STRIPE);
        acceptCard(alerted, alertedId);
        jdbc.update("UPDATE bids SET status = 'COMPLETED', updated_at = updated_at - interval '4 days' WHERE id = ?",
                alerted.bidId);
        jdbc.update("INSERT INTO admin_alerts (id, type, resolved, severity, created_at) VALUES (?, ?, false, 'WARN', "
                + "now() AT TIME ZONE 'UTC')", UUID.randomUUID(), "AMENDMENT_UNSETTLED_" + alertedId);

        job.run();

        verify(alerts).raiseOnce(eq("AMENDMENT_UNSETTLED_" + id), eq("WARN"), anyString(), anyMap());
        verify(alerts, never()).raiseOnce(eq("AMENDMENT_UNSETTLED_" + recentId), any(), anyString(), anyMap());
        verify(alerts, never()).raiseOnce(eq("AMENDMENT_UNSETTLED_" + alertedId), any(), anyString(), anyMap());
        // Une seule alerte « non soldée » dans ce passage, quelle que soit la surcharge.
        verify(alerts, times(1)).raiseOnce(startsWith("AMENDMENT_UNSETTLED_"), any(), anyString(), anyMap());
        verify(alerts, never()).raiseOnce(startsWith("AMENDMENT_UNSETTLED_"), anyString(), anyMap());
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID announcementId, UUID bidId) {
    }

    /** Trajet espèces et carte à 10 €/kg, 6 kg libres ; {@code cardReady} : compte Connect du voyageur complet. */
    private Parties parties(boolean cardReady) {
        UserEntity traveler = persistUser(cardReady);
        UserEntity sender = persistUser(false);
        UUID announcementId = persistAnnouncement(traveler.getId());
        UUID bidId = persistCashBid(announcementId, sender.getId());
        return new Parties(traveler, sender, announcementId, bidId);
    }

    private UUID create(Parties p, String kg, PaymentMethod method) {
        return service.create(p.bidId, p.sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal(kg), null, method)).amendment().id();
    }

    /** Modification carte créée : montant autorisé (webhook), puis acceptée par le voyageur, capture réussie. */
    private void acceptCard(Parties p, UUID id) throws Exception {
        String piId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        cardRail.onPaymentIntentWebhook("payment_intent.amount_capturable_updated", id, piId, "requires_capture", null,
                null);
        when(cardGateway.capturePaymentIntent(piId, "amendment-capture-" + id)).thenReturn(
                new StripeSupplementGateway.PaymentIntentView(piId, "succeeded", 3360L, "eur", null, "ch_" + id,
                        List.of("card")));
        AmendmentAcceptResponse accepted = cardRail.afterAcceptCommit(service.accept(p.bidId, id,
                p.traveler.getFirebaseUid(), new AmendmentAcceptRequest(null, null)));
        assertThat(accepted.amendment().status()).isEqualTo("ACCEPTED");
    }

    private void acceptWithWallet(Parties p, UUID id) {
        AmendmentAcceptResponse accepted = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(CommissionSource.WALLET_FIRST, null));
        assertThat(accepted.amendment().status()).isEqualTo("ACCEPTED");
    }

    private static QrScanRequest depart(UUID bidId) {
        return new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null);
    }

    /** Échéance de réponse passée (décalage relatif aux colonnes UTC écrites par Hibernate). */
    private void lapse(UUID id) {
        jdbc.update("UPDATE bid_amendments SET expires_at = created_at - interval '1 minute' WHERE id = ?", id);
    }

    private void topUp(UUID userId, String amount) {
        tx.executeWithoutResult(status -> walletService.credit(userId, "EUR", new BigDecimal(amount),
                WalletTransactionType.TOP_UP, "pi_topup_" + UUID.randomUUID(), "topup-" + UUID.randomUUID()));
    }

    /** Recrédits de la commission de la modification au voyageur. */
    private int refundCredits(UUID travelerId, UUID amendmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE user_id = ? AND type = 'REFUND' "
                + "AND payment_ref = ?", Integer.class, travelerId, "amendment-refund-" + amendmentId);
    }

    /** Trace d'audit écrite directement, {@code hoursAgo} heures dans le passé (l'audit est immuable). */
    private void insertAudit(UUID amendmentId, UUID bidId, String action, String piId, int hoursAgo) {
        jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, payload, created_at) VALUES "
                        + "('BID_AMENDMENT', ?, ?, CAST(? AS jsonb), (now() AT TIME ZONE 'UTC') - make_interval(hours => ?))",
                amendmentId, action, "{\"bidId\":\"" + bidId + "\",\"amendmentId\":\"" + amendmentId
                        + "\",\"piId\":\"" + piId + "\"}", hoursAgo);
    }

    private String column(UUID id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM bid_amendments WHERE id = ?", String.class, id);
    }

    private String bidStatus(UUID bidId) {
        return jdbc.queryForObject("SELECT status FROM bids WHERE id = ?", String.class, bidId);
    }

    private BigDecimal availableKg(UUID announcementId) {
        return jdbc.queryForObject("SELECT available_kg FROM announcements WHERE id = ?", BigDecimal.class,
                announcementId);
    }

    private BigDecimal reservedKg(UUID id) {
        return jdbc.queryForObject("SELECT capacity_reserved_kg FROM bid_amendments WHERE id = ?", BigDecimal.class, id);
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
        user.setFirebaseUid("amendment-lifecycle-it-" + UUID.randomUUID());
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

    private UUID persistCashBid(UUID announcementId, UUID senderId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("2"));
            bid.setCommissionRate(new BigDecimal("0.12"));
            bid.setStatus(BidStatus.ACCEPTED);
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
