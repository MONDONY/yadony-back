package com.yadony.api.payments.fee;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.fee.CancellationFeeEntity;
import com.yadony.api.cancellation.fee.CancellationFeeReason;
import com.yadony.api.cancellation.fee.CancellationFeeRepository;
import com.yadony.api.cancellation.fee.CancellationFeeStatus;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.RefundProcessor;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccountEntity;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccountRepository;
import com.yadony.api.payments.mobilemoney.PayoutAccountFixtures;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayRefundRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

/**
 * Frais d'annulation sur le rail mobile money, sur la vraie pile : remboursement PARTIEL pawaPay du dépôt
 * ({@link RefundProcessor}), retenue réglée, versement pawaPay du net au voyageur rattaché à la retenue (purpose
 * {@code CANCELLATION_FEE}, index {@code uq_pawapay_ops_live_per_cancellation_fee} de V394), issue pawaPay appliquée
 * par les vrais écouteurs, planificateur. PostgreSQL (Flyway jusqu'à V394), barème pawaPay réel d'application.yml
 * (ORANGE_SEN : dépôt 2 %, remboursement 1,8 %), taux XOF réel (655,957) ; seul le client HTTP pawaPay est simulé.
 *
 * <p>65 600 F payés dont 3 124 F de commission, retenue 30 % plafonnée à 30 € = 19 678 F ; rendu 45 922 F ; frais
 * 1 312 + 826,60 = 2 139 F ; commission 877 F ; net voyageur 16 662 F.
 */
@SpringBootTest(properties = {"yadony.pawapay.poll-cron=-", "yadony.pawapay.deadline-cron=-",
        "yadony.pawapay.balance-cron=-"})
@ActiveProfiles({"e2e", "cancellation-fee"})
@DisplayName("Frais d'annulation, rail mobile money (PostgreSQL, pawaPay simulé)")
class CancellationFeeMobileMoneyIT {

    private static final String PAYER = "221770000001";
    private static final String ACCOUNT_MSISDN = "221770000002";

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

    @MockitoBean PawapayClient client;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    @Autowired RefundProcessor refundProcessor;
    @Autowired com.yadony.api.cancellation.fee.CancellationFeeAdminService adminService;
    @Autowired CancellationFeePayoutScheduler scheduler;
    @Autowired PawapayOperationService operations;
    @Autowired PawapayOperationRepository operationRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired CancellationFeeRepository feeRepository;
    @Autowired UserRepository userRepository;
    @Autowired MobileMoneyPayoutAccountRepository accountRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.yadony.api.cancellation.fee.CancellationFeePreviewService previewService;
    @Autowired com.yadony.api.cancellation.fee.SenderCancellationFeeRecorder recorder;

    private UUID travelerId;
    private UUID senderId;
    private UUID bidId;
    private UUID paymentId;
    private UUID feeId;
    private UUID depositId;

    @Autowired CancellationFeePayoutService payoutService;

    @BeforeEach
    void setUp() {
        when(notificationDispatcher.messagesFor(any())).thenReturn(com.yadony.api.common.i18n.TestMessages.fr());
        when(client.initiateRefund(any())).thenReturn(PawapayInitiationResult.accepted());
        when(client.initiatePayout(any())).thenReturn(PawapayInitiationResult.accepted());
        UserEntity traveler = persistUser(Role.TRAVELER);
        travelerId = traveler.getId();
        UserEntity sender = persistUser(Role.SENDER);
        senderId = sender.getId();
        bidId = persistCancelledBid(persistTrip(traveler), sender).getId();
        paymentId = persistEscrow();
        depositId = persistCompletedDeposit();
        feeId = persistDueFee(sender);
    }

    @Test
    @DisplayName("remboursement partiel, puis versement du net une fois le remboursement abouti : PAID_OUT, un seul versement")
    void partialRefund_thenFeePaidOnceTheRefundCompletes() throws Exception {
        withMobileMoneyAccount();

        assertThat(refund()).isTrue();

        // Remboursement PARTIEL du dépôt : 45 922 F, jamais 65 600.
        ArgumentCaptor<PawapayRefundRequest> refundRequest = ArgumentCaptor.forClass(PawapayRefundRequest.class);
        verify(client).initiateRefund(refundRequest.capture());
        assertThat(refundRequest.getValue().amount()).isEqualByComparingTo("45922");
        assertThat(refundRequest.getValue().depositId()).isEqualTo(depositId);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", BigDecimal.class, paymentId))
                .isEqualByComparingTo("45922");
        CancellationFeeEntity fee = feeRepository.findById(feeId).orElseThrow();
        assertThat(fee.getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
        assertThat(fee.getGrossFee()).isEqualByComparingTo("19678");
        assertThat(fee.getRefundFees()).isEqualByComparingTo("2139");
        assertThat(fee.getCommission()).isEqualByComparingTo("877");
        assertThat(fee.getTravelerNet()).isEqualByComparingTo("16662");
        assertThat(fee.getCurrency()).isEqualTo("XOF");

        // Écouteur du règlement passé après le commit : le remboursement de l'expéditeur est encore en vol, rien ne part.
        verify(client, never()).initiatePayout(any());
        assertThat(openAlerts()).isZero();

        UUID refundId = operationId(paymentId, "REFUND");
        operations.apply(refundId, PawapayOperationStatus.COMPLETED, null, null, providerTx(), null, "{}",
                PawapayOperationService.Source.CALLBACK);
        ageRetainedRow();

        assertThat(scheduler.run()).as("soumis, pas encore arrivé").isZero();

        ArgumentCaptor<PawapayPayoutRequest> payoutRequest = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client).initiatePayout(payoutRequest.capture());
        assertThat(payoutRequest.getValue().amount()).isEqualByComparingTo("16662");
        assertThat(payoutRequest.getValue().currency()).isEqualTo("XOF");
        assertThat(payoutRequest.getValue().phoneNumber()).isEqualTo(ACCOUNT_MSISDN);
        assertThat(payoutRequest.getValue().clientReferenceId()).isEqualTo("fee-" + feeId);
        UUID payoutId = payoutRequest.getValue().payoutId();
        Map<String, Object> row = jdbc.queryForMap("SELECT purpose, kind, payment_id, user_id, cancellation_fee_id, amount "
                + "FROM pawapay_operations WHERE id = ?", payoutId);
        assertThat(row.get("purpose")).isEqualTo("CANCELLATION_FEE");
        assertThat(row.get("kind")).isEqualTo("PAYOUT");
        assertThat(row.get("payment_id")).as("jamais lié au paiement du colis").isNull();
        assertThat(row.get("user_id")).isEqualTo(travelerId);
        assertThat(row.get("cancellation_fee_id")).isEqualTo(feeId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("16662");
        assertThat(audits("CANCELLATION_FEE_PAYOUT_SUBMITTED")).isEqualTo(1);

        // Rejeu de l'événement puis nouveau passage : le versement vivant est repris, jamais doublé.
        tx.executeWithoutResult(status -> events.publishEvent(new CancellationFeeRetainedEvent(bidId)));
        assertThat(scheduler.run()).isZero();
        verify(client, times(1)).initiatePayout(any());
        assertThat(feePayouts()).isEqualTo(1);
        // La base refuse elle aussi un second versement vivant pour la même retenue.
        assertThatThrownBy(() -> operationRepository.saveAndFlush(PawapayOperationEntity.forCancellationFee(
                UUID.randomUUID(), travelerId, feeId, new BigDecimal("16662"), "XOF", "ORANGE_SEN", "SN",
                ACCOUNT_MSISDN)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_pawapay_ops_live_per_cancellation_fee");

        operations.apply(payoutId, PawapayOperationStatus.COMPLETED, null, null, providerTx(), null, "{}",
                PawapayOperationService.Source.CALLBACK);

        awaitFeeStatus(CancellationFeeStatus.PAID_OUT);
        assertThat(feeRepository.findById(feeId).orElseThrow().getPayoutRef()).isEqualTo(payoutId.toString());
        awaitAudits("CANCELLATION_FEE_PAID_OUT", 1);
        // Le versement du colis livré ne s'en mêle pas : aucune trace de versement sur le paiement remboursé.
        Thread.sleep(300);
        assertThat(paymentAudits("MM_PAYOUT_COMPLETED")).isZero();
        assertThat(openAlerts()).isZero();

        // Notifications : l'expéditeur a appris la retenue au règlement, le voyageur son dédommagement au versement.
        verify(notificationDispatcher, timeout(10_000)).notifyUser(eq(senderId), eq("Frais d'annulation"),
                eq("Frais de 19\u00A0678\u00A0F CFA retenus, 45\u00A0922\u00A0F CFA vous sont remboursés."), anyMap());
        verify(notificationDispatcher, timeout(10_000)).notifyUser(eq(travelerId), eq("Dédommagement reçu"),
                eq("16\u00A0662\u00A0F CFA vous sont versés en dédommagement de l'annulation."), anyMap());
        // Rejeu de l'issue du versement et nouveau passage : déjà versé, aucune seconde notification.
        payoutService.mobileMoneyPayoutCompleted(feeId, payoutId);
        tx.executeWithoutResult(status -> events.publishEvent(new CancellationFeeRetainedEvent(bidId)));
        assertThat(scheduler.run()).isZero();
        verify(notificationDispatcher, after(1_000).times(1)).notifyUser(eq(travelerId), anyString(), anyString(),
                anyMap());
        verify(notificationDispatcher, times(1)).notifyUser(eq(senderId), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("voyageur sans compte mobile money : en attente, une alerte, puis versé par le planificateur")
    void travelerWithoutMobileMoneyAccount_pendingWithOneAlert_thenPaidByTheScheduler() throws Exception {
        assertThat(refund()).isTrue();
        operations.apply(operationId(paymentId, "REFUND"), PawapayOperationStatus.COMPLETED, null, null, providerTx(),
                null, "{}", PawapayOperationService.Source.CALLBACK);
        ageRetainedRow();

        assertThat(scheduler.run()).isZero();

        assertThat(status()).isEqualTo(CancellationFeeStatus.PAYOUT_PENDING);
        assertThat(openAlerts()).isEqualTo(1);
        assertThat(audits("CANCELLATION_FEE_PAYOUT_PENDING")).isEqualTo(1);
        verify(client, never()).initiatePayout(any());

        // Passage suivant, toujours sans compte : ni seconde alerte, ni second audit, rien ne part.
        assertThat(scheduler.run()).isZero();
        assertThat(openAlerts()).isEqualTo(1);
        assertThat(audits("CANCELLATION_FEE_PAYOUT_PENDING")).isEqualTo(1);
        verify(client, never()).initiatePayout(any());

        // Compte ajouté : le planificateur reprend la ligne en attente (quel que soit son âge) et verse.
        withMobileMoneyAccount();
        assertThat(scheduler.run()).isZero();
        ArgumentCaptor<PawapayPayoutRequest> payoutRequest = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client).initiatePayout(payoutRequest.capture());
        operations.apply(payoutRequest.getValue().payoutId(), PawapayOperationStatus.COMPLETED, null, null, providerTx(),
                null, "{}", PawapayOperationService.Source.CALLBACK);

        awaitFeeStatus(CancellationFeeStatus.PAID_OUT);
        awaitOpenAlerts(0);
    }

    @Test
    @DisplayName("versement échoué chez pawaPay : en attente avec alerte, puis nouvel essai par une nouvelle opération")
    void payoutFailed_pendingThenRetriedWithANewOperation() throws Exception {
        withMobileMoneyAccount();
        assertThat(refund()).isTrue();
        operations.apply(operationId(paymentId, "REFUND"), PawapayOperationStatus.COMPLETED, null, null, providerTx(),
                null, "{}", PawapayOperationService.Source.CALLBACK);
        ageRetainedRow();
        scheduler.run();
        ArgumentCaptor<PawapayPayoutRequest> first = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client).initiatePayout(first.capture());

        operations.apply(first.getValue().payoutId(), PawapayOperationStatus.FAILED, "RECIPIENT_NOT_FOUND", "inconnu",
                null, null, "{}", PawapayOperationService.Source.CALLBACK);

        awaitFeeStatus(CancellationFeeStatus.PAYOUT_PENDING);
        awaitOpenAlerts(1);

        scheduler.run();

        ArgumentCaptor<PawapayPayoutRequest> both = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client, times(2)).initiatePayout(both.capture());
        assertThat(both.getAllValues().get(1).payoutId()).isNotEqualTo(first.getValue().payoutId());
        assertThat(feePayouts()).isEqualTo(2);
    }

    @Test
    @DisplayName("remboursement partiel refusé par pawaPay : paiement de nouveau en séquestre, retenue toujours due")
    void partialRefundRejected_paymentBackInEscrow_feeStillDue_thenRefundedOnRetry() {
        when(client.initiateRefund(any()))
                .thenReturn(new PawapayInitiationResult(PawapayInitiationResult.Outcome.REJECTED, "AMOUNT_OUT_OF_BOUNDS",
                        "refusé"))
                .thenReturn(PawapayInitiationResult.accepted());

        assertThatThrownBy(this::refund).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("ESCROW");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", BigDecimal.class, paymentId))
                .isNull();
        assertThat(status()).isEqualTo(CancellationFeeStatus.DUE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND resolved = false",
                Long.class, "PAWAPAY_REFUND_REJECTED_" + paymentId)).isEqualTo(1L);

        // Un nouvel essai (écouteur rejoué, geste admin « Rembourser ») soumet un nouveau remboursement partiel.
        assertThat(refund()).isTrue();

        ArgumentCaptor<PawapayRefundRequest> requests = ArgumentCaptor.forClass(PawapayRefundRequest.class);
        verify(client, times(2)).initiateRefund(requests.capture());
        assertThat(requests.getAllValues()).extracting(PawapayRefundRequest::amount)
                .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("45922"));
        assertThat(status()).isEqualTo(CancellationFeeStatus.RETAINED);
    }

    @Test
    @DisplayName("partiel refusé, l'admin renonce à la retenue : le remboursement suivant rend le dépôt entier")
    void partialRefundRejected_adminWaivesTheFee_nextRefundReturnsTheWholeDeposit() {
        when(client.initiateRefund(any()))
                .thenReturn(new PawapayInitiationResult(PawapayInitiationResult.Outcome.REJECTED, "AMOUNT_OUT_OF_BOUNDS",
                        "refusé"))
                .thenReturn(PawapayInitiationResult.accepted());
        assertThatThrownBy(this::refund).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo(CancellationFeeStatus.DUE);
        UUID adminId = UUID.randomUUID();

        adminService.waive(bidId, adminId, "Partiel refusé par l'opérateur, remboursement intégral");

        assertThat(status()).isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_id, payload::text AS payload FROM audit_log "
                + "WHERE entity_id = ? AND action = 'CANCELLATION_FEE_NOT_APPLICABLE'", bidId);
        assertThat(audit.get("actor_id")).isEqualTo(adminId);
        assertThat((String) audit.get("payload")).contains("ADMIN_WAIVED").contains("Partiel refusé par l'opérateur");

        assertThat(refund()).isTrue();

        ArgumentCaptor<PawapayRefundRequest> requests = ArgumentCaptor.forClass(PawapayRefundRequest.class);
        verify(client, times(2)).initiateRefund(requests.capture());
        assertThat(requests.getAllValues().get(1).amount()).as("le dépôt entier").isEqualByComparingTo("65600");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", BigDecimal.class, paymentId))
                .isNull();
        assertThat(status()).isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        assertThat(audits("CANCELLATION_FEE_RETAINED")).isZero();
    }

    @Test
    @DisplayName("plafond : seuls les versements FAILED comptent, l'arrêt lève une alerte à part, la relance admin verse")
    void onlyFailedPayoutsCount_stopRaisesADistinctAlert_adminRetryPays() throws Exception {
        withMobileMoneyAccount();
        assertThat(refund()).isTrue();
        operations.apply(operationId(paymentId, "REFUND"), PawapayOperationStatus.COMPLETED, null, null, providerTx(),
                null, "{}", PawapayOperationService.Source.CALLBACK);
        // Historique : trois refus à la soumission (rien n'est parti) et deux échecs pawaPay.
        deadFeePayout(PawapayOperationStatus.SUBMIT_REJECTED);
        deadFeePayout(PawapayOperationStatus.SUBMIT_REJECTED);
        deadFeePayout(PawapayOperationStatus.SUBMIT_REJECTED);
        deadFeePayout(PawapayOperationStatus.FAILED);
        deadFeePayout(PawapayOperationStatus.FAILED);
        ageRetainedRow();

        // Deux échecs seulement : un nouveau versement part malgré les trois refus.
        scheduler.run();
        ArgumentCaptor<PawapayPayoutRequest> first = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client).initiatePayout(first.capture());
        operations.apply(first.getValue().payoutId(), PawapayOperationStatus.FAILED, "RECIPIENT_NOT_FOUND", "inconnu",
                null, null, "{}", PawapayOperationService.Source.CALLBACK);
        awaitFeeStatus(CancellationFeeStatus.PAYOUT_PENDING);
        assertThat(stopAlerts()).isZero();

        // Troisième échec : plus d'essai automatique, alerte d'arrêt, nouveau motif enregistré et audité.
        scheduler.run();
        verify(client, times(1)).initiatePayout(any());
        assertThat(stopAlerts()).isEqualTo(1);
        assertThat(feeRepository.findById(feeId).orElseThrow().getPayoutPendingReason())
                .isEqualTo("TOO_MANY_FAILED_PAYOUTS");
        assertThat(jdbc.queryForList("SELECT payload::text FROM audit_log WHERE entity_id = ? "
                + "AND action = 'CANCELLATION_FEE_PAYOUT_PENDING' ORDER BY created_at", String.class, bidId))
                .hasSize(2)
                .last().satisfies(payload -> assertThat(payload).contains("TOO_MANY_FAILED_PAYOUTS"));
        // Passage suivant, même cause : ni versement, ni seconde alerte, ni audit de plus.
        scheduler.run();
        verify(client, times(1)).initiatePayout(any());
        assertThat(stopAlerts()).isEqualTo(1);
        assertThat(audits("CANCELLATION_FEE_PAYOUT_PENDING")).isEqualTo(2);

        // Relance admin : le plafond repart de zéro, versement tenté aussitôt par le chemin habituel.
        UUID adminId = UUID.randomUUID();
        adminService.retryPayout(feeId, adminId, "Numéro du voyageur corrigé avec le support");

        ArgumentCaptor<PawapayPayoutRequest> both = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client, times(2)).initiatePayout(both.capture());
        assertThat(stopAlerts()).as("alerte d'arrêt close par la relance").isZero();
        assertThat(audits("CANCELLATION_FEE_PAYOUT_RETRY_REQUESTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE entity_id = ? "
                + "AND action = 'CANCELLATION_FEE_PAYOUT_RETRY_REQUESTED'", UUID.class, bidId)).isEqualTo(adminId);
        operations.apply(both.getAllValues().get(1).payoutId(), PawapayOperationStatus.COMPLETED, null, null,
                providerTx(), null, "{}", PawapayOperationService.Source.CALLBACK);

        awaitFeeStatus(CancellationFeeStatus.PAID_OUT);
        awaitOpenAlerts(0);
    }

    /**
     * Revue tâche 7, tour 2 : un remboursement partiel resté CREATED (pawaPay muet) survit à l'annulation de la
     * transaction. Renoncer à la retenue est refusé tant qu'il vit ; et si la retenue a été close par un autre chemin,
     * le remboursement repris n'est jamais présenté comme intégral (montant enregistré, une alerte).
     */
    @Test
    @DisplayName("partiel resté CREATED, transaction annulée : renonciation refusée (409), puis reprise sans retenue alertée")
    void partialRefundLeftCreated_waiveRefused_thenReusedWithoutAFee_amountRecordedAndAlerted() {
        when(client.initiateRefund(any())).thenThrow(new org.springframework.web.client.RestClientException("timeout"));

        assertThatThrownBy(this::refund).isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("ESCROW");
        assertThat(status()).isEqualTo(CancellationFeeStatus.DUE);
        UUID refundId = operationId(paymentId, "REFUND");
        Map<String, Object> refundRow = jdbc.queryForMap("SELECT status, amount FROM pawapay_operations WHERE id = ?",
                refundId);
        assertThat(refundRow.get("status")).isEqualTo("CREATED");
        assertThat((BigDecimal) refundRow.get("amount")).isEqualByComparingTo("45922");

        assertThatThrownBy(() -> adminService.waive(bidId, UUID.randomUUID(),
                "Partiel refusé par l'opérateur, remboursement intégral"))
                .isInstanceOfSatisfying(com.yadony.api.common.YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("sender-refund-in-flight");
                });
        assertThat(status()).isEqualTo(CancellationFeeStatus.DUE);
        assertThat(audits("CANCELLATION_FEE_NOT_APPLICABLE")).isZero();

        // Défense en profondeur : retenue close par un autre chemin, puis reprise du remboursement vivant.
        jdbc.update("UPDATE cancellation_fees SET status = 'NOT_APPLICABLE' WHERE id = ?", feeId);
        assertThat(refund()).isTrue();

        verify(client, times(1)).initiateRefund(any());
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", BigDecimal.class, paymentId))
                .as("jamais présenté comme remboursé en entier").isEqualByComparingTo("45922");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND resolved = false",
                Long.class, "MM_PARTIAL_REFUND_" + paymentId)).isEqualTo(1L);
    }

    private void deadFeePayout(PawapayOperationStatus status) {
        PawapayOperationEntity op = PawapayOperationEntity.forCancellationFee(UUID.randomUUID(), travelerId, feeId,
                new BigDecimal("16662"), "XOF", "ORANGE_SEN", "SN", ACCOUNT_MSISDN);
        op.setStatus(status);
        operationRepository.saveAndFlush(op);
    }

    private long stopAlerts() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND resolved = false",
                Long.class, CancellationFeePayoutService.STOPPED_ALERT_PREFIX + feeId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("aperçu puis vrai règlement, mobile money XOF : même brut plafonné, même montant rendu au dépôt")
    void preview_thenRealSettlement_mobileMoney_sameGrossAndRefund() {
        UserEntity sender = persistUser(Role.SENDER);
        BidEntity bid = persistCancelledBid(persistTrip(userRepository.findById(travelerId).orElseThrow()), sender);
        jdbc.update("UPDATE bids SET status = 'ACCEPTED' WHERE id = ?", bid.getId());
        bidId = bid.getId();
        paymentId = persistEscrow();           // 65 600 F
        depositId = persistCompletedDeposit(); // dépôt abouti du même montant

        com.yadony.api.cancellation.dto.CancellationFeePreviewResponse preview =
                previewService.preview(sender.getFirebaseUid(), bidId);
        assertThat(preview.currency()).isEqualTo("XOF");
        assertThat(preview.rate()).isEqualByComparingTo("0.30");
        // 65 600 × 30 % = 19 680, plafonné à 30 € convertis vers le bas : 19 678.
        assertThat(preview.fee()).isEqualByComparingTo("19678");
        assertThat(preview.refundAmount()).isEqualByComparingTo("45922");

        tx.executeWithoutResult(status -> recorder.record(bidId, CancellationFeeReason.SENDER_CANCEL));
        assertThat(refund()).isTrue();

        ArgumentCaptor<PawapayRefundRequest> refundRequest = ArgumentCaptor.forClass(PawapayRefundRequest.class);
        verify(client).initiateRefund(refundRequest.capture());
        assertThat(refundRequest.getValue().amount()).isEqualByComparingTo(preview.refundAmount());
        CancellationFeeEntity fee = feeRepository.findByBidId(bidId).orElseThrow();
        assertThat(fee.getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
        assertThat(fee.getRate()).isEqualByComparingTo(preview.rate());
        assertThat(fee.getGrossFee()).isEqualByComparingTo(preview.fee());
        assertThat(fee.getCurrency()).isEqualTo(preview.currency());
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", BigDecimal.class, paymentId))
                .isEqualByComparingTo(preview.refundAmount());
    }

    private boolean refund() {
        return refundProcessor.processRefund(paymentId, "PAYMENT_REFUNDED_BID_REJECTED", bidId,
                Map.of("reason", "bid_rejected"));
    }

    /** Identifiant opérateur unique : {@code uq_pawapay_ops_provider_transaction} vaut pour toute la base partagée. */
    private static String providerTx() {
        return "tx-" + UUID.randomUUID();
    }

    private CancellationFeeStatus status() {
        return feeRepository.findById(feeId).orElseThrow().getStatus();
    }

    /** Ligne réglée il y a 11 min, écrite comme l'entité l'aurait écrite (LocalDateTime UTC). */
    private void ageRetainedRow() {
        jdbc.update("UPDATE cancellation_fees SET updated_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11), feeId);
    }

    private UUID operationId(UUID payment, String kind) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM pawapay_operations WHERE payment_id = ? AND kind = ? "
                + "AND status NOT IN ('FAILED', 'SUBMIT_REJECTED')", UUID.class, payment, kind);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private long feePayouts() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pawapay_operations WHERE cancellation_fee_id = ?", Long.class,
                feeId);
        return n == null ? 0 : n;
    }

    private long audits(String action) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Long.class, bidId, action);
        return n == null ? 0 : n;
    }

    private long paymentAudits(String action) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Long.class, paymentId, action);
        return n == null ? 0 : n;
    }

    private long openAlerts() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND resolved = false",
                Long.class, CancellationFeePayoutService.ALERT_PREFIX + feeId);
        return n == null ? 0 : n;
    }

    private void awaitFeeStatus(CancellationFeeStatus expected) throws InterruptedException {
        Supplier<CancellationFeeStatus> current = this::status;
        for (int i = 0; i < 100 && current.get() != expected; i++) {
            Thread.sleep(100);
        }
        assertThat(current.get()).isEqualTo(expected);
    }

    private void awaitAudits(String action, long expected) throws InterruptedException {
        for (int i = 0; i < 100 && audits(action) != expected; i++) {
            Thread.sleep(100);
        }
        assertThat(audits(action)).isEqualTo(expected);
    }

    private void awaitOpenAlerts(long expected) throws InterruptedException {
        for (int i = 0; i < 100 && openAlerts() != expected; i++) {
            Thread.sleep(100);
        }
        assertThat(openAlerts()).isEqualTo(expected);
    }

    private void withMobileMoneyAccount() {
        MobileMoneyPayoutAccountEntity account = new MobileMoneyPayoutAccountEntity(travelerId, "ORANGE_SEN", "SN", "XOF",
                ACCOUNT_MSISDN, "+221 •••• 02", Instant.now());
        PayoutAccountFixtures.setMigrated(account, true);
        accountRepository.saveAndFlush(account);
    }

    private UUID persistEscrow() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setRail(PaymentRail.PAWAPAY);
        p.setAmount(new BigDecimal("65600"));
        p.setCommissionAmount(new BigDecimal("3124"));
        p.setCurrency("XOF");
        p.setStatus(PaymentStatus.ESCROW);
        return paymentRepository.saveAndFlush(p).getId();
    }

    private UUID persistCompletedDeposit() {
        PawapayOperationEntity deposit = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                paymentId, null, new BigDecimal("65600"), "XOF", "ORANGE_SEN", "SN", PAYER);
        deposit.setStatus(PawapayOperationStatus.COMPLETED);
        return operationRepository.saveAndFlush(deposit).getId();
    }

    private UUID persistDueFee(UserEntity sender) {
        CancellationFeeEntity fee = new CancellationFeeEntity();
        fee.setBidId(bidId);
        fee.setSenderId(sender.getId());
        fee.setTravelerId(travelerId);
        fee.setRate(new BigDecimal("0.30"));
        fee.setReason(CancellationFeeReason.SENDER_CANCEL);
        fee.setStatus(CancellationFeeStatus.DUE);
        fee.setGrossFee(BigDecimal.ZERO);
        fee.setRefundFees(BigDecimal.ZERO);
        fee.setCommission(BigDecimal.ZERO);
        fee.setTravelerNet(BigDecimal.ZERO);
        fee.setCurrency("XOF");
        return feeRepository.saveAndFlush(fee).getId();
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("fee-mm-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private AnnouncementEntity persistTrip(UserEntity traveler) {
        OffsetDateTime departureAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(12);
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(departureAt.toLocalDate());
        a.setDepartureAt(departureAt);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport de Dakar");
        a.setDeliveryLat(new BigDecimal("14.670833"));
        a.setDeliveryLng(new BigDecimal("-17.073056"));
        a.setAvailableKg(new BigDecimal("15.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("6500"));
        a.setCurrency("XOF");
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.saveAndFlush(a);
    }

    private BidEntity persistCancelledBid(AnnouncementEntity trip, UserEntity sender) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setCurrency("XOF");
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        bid.setStatus(BidStatus.CANCELLED);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }
}
