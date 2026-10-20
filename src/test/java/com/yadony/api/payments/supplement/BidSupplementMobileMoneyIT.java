package com.yadony.api.payments.supplement;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccountEntity;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccountRepository;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rail mobile money du supplément de bout en bout, sur un vrai PostgreSQL (V338 : index
 * {@code uq_pawapay_ops_live_per_supplement}, CHECK de purpose), pawaPay simulé (plan §9 B3.4) :
 * demande → acceptation → dépôt (purpose {@code BID_SUPPLEMENT}, jamais de {@code payment_id}) →
 * callback → payé ; expiration retenue tant que le dépôt est ouvert ; versement après le payout
 * principal, vers la même destination ; remboursement quand le colis finit sans livraison.
 */
@SpringBootTest(properties = {"yadony.bid-supplement.enabled=true", "yadony.bid-supplement.reconcile-enabled=false",
        "yadony.pawapay.enabled=true", "yadony.pawapay.poll-cron=-", "yadony.pawapay.deadline-cron=-",
        "yadony.pawapay.balance-cron=-"})
@ActiveProfiles("e2e")
class BidSupplementMobileMoneyIT {

    private static final String PAYER = "221770000001";
    /** Numéro figé dans le payout principal : périmé, il ne doit JAMAIS servir au supplément. */
    private static final String PRINCIPAL_PAYEE = "221770000099";
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
    @MockitoBean PawapayProviderResolver resolver;

    @Autowired BidSupplementService supplementService;
    @Autowired BidSupplementPaymentFlow paymentFlow;
    @Autowired BidSupplementMobileMoneyRail mobileMoney;
    @Autowired PawapayOperationService operations;
    @Autowired PawapayOperationRepository operationRepository;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository userRepository;
    @Autowired MobileMoneyPayoutAccountRepository accountRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void pawapay() {
        PawapayProviderConfig.Limits open = new PawapayProviderConfig.Limits(new BigDecimal("100"),
                new BigDecimal("2000000"), "PROVIDER_AUTH", null);
        PawapayProviderConfig orange = new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", open, open, open);
        when(resolver.resolve(eq(PAYER), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), isNull(), anyString()))
                .thenReturn(new PawapayProviderResolver.Resolved("ORANGE_SEN", "SN", PAYER, orange));
        when(client.initiateDeposit(any())).thenReturn(PawapayInitiationResult.accepted());
        when(client.initiatePayout(any())).thenReturn(PawapayInitiationResult.accepted());
        when(client.initiateRefund(any())).thenReturn(PawapayInitiationResult.accepted());
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", orange));
    }

    @Test
    void depotDuSupplement_purposeDedie_unSeulDepotVivant_puisPaye() throws Exception {
        Parties p = parties();
        UUID supplementId = createSupplement(p);

        SupplementAcceptResponse accepted = paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid());
        assertThat(accepted.rail()).isEqualTo("PAWAPAY");
        assertThat(accepted.clientSecret()).isNull();
        assertThat(accepted.supplement().status()).isEqualTo("PAYMENT_PENDING");

        MobileMoneyPaymentStatusResponse initiated = mobileMoney.initiate(p.bidId, supplementId,
                p.sender.getFirebaseUid(), null, null);
        assertThat(initiated.paymentStatus()).isEqualTo("PENDING");
        assertThat(initiated.amount()).isEqualByComparingTo(new BigDecimal(column(supplementId, "gross_amount")));
        UUID depositId = initiated.deposit().id();
        assertThat(initiated.deposit().status()).isEqualTo("ACCEPTED");

        // Second tap : le dépôt vivant est relu, jamais doublé.
        assertThat(mobileMoney.initiate(p.bidId, supplementId, p.sender.getFirebaseUid(), null, null).deposit().id())
                .isEqualTo(depositId);
        ArgumentCaptor<PawapayDepositRequest> request = ArgumentCaptor.forClass(PawapayDepositRequest.class);
        verify(client, times(1)).initiateDeposit(request.capture());
        assertThat(request.getValue().clientReferenceId()).isEqualTo("supp-" + supplementId);

        Map<String, Object> row = jdbc.queryForMap("SELECT purpose, payment_id, user_id, bid_supplement_id, kind "
                + "FROM pawapay_operations WHERE id = ?", depositId);
        assertThat(row.get("purpose")).isEqualTo("BID_SUPPLEMENT");
        assertThat(row.get("payment_id")).isNull();
        assertThat(row.get("user_id")).isEqualTo(p.sender.getId());
        assertThat(row.get("bid_supplement_id")).isEqualTo(supplementId);

        // Index uq_pawapay_ops_live_per_supplement : un second dépôt vivant est refusé par la base,
        // et par la garde applicative (409).
        assertThatThrownBy(() -> operationRepository.saveAndFlush(PawapayOperationEntity.forSupplement(
                UUID.randomUUID(), PawapayOperationKind.DEPOSIT, p.sender.getId(), supplementId, null,
                new BigDecimal("13715"), "XOF", "ORANGE_SEN", "SN", PAYER)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_pawapay_ops_live_per_supplement");
        assertThatThrownBy(() -> operations.createForSupplement(PawapayOperationKind.DEPOSIT, p.sender.getId(),
                supplementId, null, new BigDecimal("13715"), "XOF", "ORANGE_SEN", "SN", PAYER))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("mobile-money-operation-in-progress"));

        operations.apply(depositId, PawapayOperationStatus.COMPLETED, null, null, "tx-dep", null, "{}",
                PawapayOperationService.Source.CALLBACK);

        awaitStatus(supplementId, "PAID");
        assertThat(mobileMoney.status(p.bidId, supplementId, p.traveler.getFirebaseUid()).paymentStatus())
                .isEqualTo("ESCROW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE bid_id = ?", Integer.class, p.bidId))
                .as("jamais de ligne payments pour un supplément").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, supplementId, "BID_SUPPLEMENT_PAID")).isEqualTo(1);
    }

    @Test
    void demandeEchueAvecDepotOuvert_nExpirePas_puisExpireUneFoisLeDepotEchoue() throws Exception {
        Parties p = parties();
        UUID supplementId = createSupplement(p);
        paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid());
        UUID depositId = mobileMoney.initiate(p.bidId, supplementId, p.sender.getFirebaseUid(), null, null)
                .deposit().id();
        // Par JPA, pas par JDBC : Hibernate écrit les TIMESTAMP en UTC (hibernate.jdbc.time_zone).
        tx.executeWithoutResult(status -> entityManager.find(BidSupplementEntity.class, supplementId)
                .setExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1)));

        assertThat(supplementService.expire(supplementId)).as("dépôt encore ouvert").isFalse();
        assertThat(column(supplementId, "status")).isEqualTo("PAYMENT_PENDING");

        operations.apply(depositId, PawapayOperationStatus.FAILED, "PAYER_LIMIT_REACHED", "non", null, null, "{}",
                PawapayOperationService.Source.CALLBACK);
        awaitAudit(supplementId, "BID_SUPPLEMENT_MM_DEPOSIT_FAILED");

        assertThat(supplementService.expire(supplementId)).isTrue();
        assertThat(column(supplementId, "status")).isEqualTo("EXPIRED");
        assertThat(mobileMoney.status(p.bidId, supplementId, p.sender.getFirebaseUid()).paymentStatus())
                .isEqualTo("CANCELLED");
    }

    @Test
    void versementApresLePayoutPrincipal_versLeCompteDuCodeDuDepot_puisVerse() throws Exception {
        Parties p = parties();
        UUID supplementId = payDeposit(p);
        UUID paymentId = persistReleasedPrincipal(p.bidId);
        jdbc.update("UPDATE bids SET status = 'COMPLETED' WHERE id = ?", p.bidId);

        tx.executeWithoutResult(status -> publisher.publishEvent(new PaymentReleasedEvent(p.bidId,
                p.traveler.getId(), p.sender.getId(), new BigDecimal("50000"), "XOF", true)));

        awaitStatus(supplementId, "PAYOUT_PENDING");
        // Le statut passe à PAYOUT_PENDING (réservation) AVANT l'appel pawaPay, fait de façon asynchrone :
        // sans attente, la vérification précédait parfois l'appel sous charge.
        ArgumentCaptor<PawapayPayoutRequest> payout = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client, timeout(10_000).times(1)).initiatePayout(payout.capture());
        // Le compte du code du dépôt du supplément (celui que le verrou protège), pas le numéro figé du principal.
        assertThat(payout.getValue().phoneNumber()).isEqualTo(ACCOUNT_MSISDN).isNotEqualTo(PRINCIPAL_PAYEE);
        assertThat(payout.getValue().amount()).isEqualByComparingTo("13000");
        UUID payoutId = payout.getValue().payoutId();
        Map<String, Object> row = jdbc.queryForMap("SELECT purpose, payment_id, user_id FROM pawapay_operations "
                + "WHERE id = ?", payoutId);
        assertThat(row.get("purpose")).isEqualTo("BID_SUPPLEMENT");
        assertThat(row.get("payment_id")).isNull();
        assertThat(row.get("user_id")).isEqualTo(p.traveler.getId());

        operations.apply(payoutId, PawapayOperationStatus.COMPLETED, null, null, "tx-pay", null, "{}",
                PawapayOperationService.Source.CALLBACK);

        awaitStatus(supplementId, "PAID_OUT");
        Thread.sleep(300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, paymentId, "MM_PAYOUT_COMPLETED"))
                .as("l'écouteur du paiement principal ignore le payout du supplément").isZero();
    }

    @Test
    void colisRefuseApresPaiement_depotDuSupplementRembourse() throws Exception {
        Parties p = parties();
        UUID supplementId = payDeposit(p);

        jdbc.update("UPDATE bids SET status = 'PARCEL_REFUSED' WHERE id = ?", p.bidId);
        assertThat(supplementService.onBidEnded(p.bidId, "PARCEL_REFUSED")).isTrue();

        UUID refundId = awaitOperation(supplementId, "REFUND");
        assertThat(jdbc.queryForObject("SELECT related_operation_id FROM pawapay_operations WHERE id = ?", UUID.class,
                refundId)).isEqualTo(jdbc.queryForObject("SELECT id FROM pawapay_operations WHERE bid_supplement_id = ? "
                + "AND kind = 'DEPOSIT'", UUID.class, supplementId));
        assertThat(column(supplementId, "status")).isEqualTo("REFUND_PENDING");

        operations.apply(refundId, PawapayOperationStatus.COMPLETED, null, null, "tx-ref", null, "{}",
                PawapayOperationService.Source.CALLBACK);

        awaitStatus(supplementId, "REFUNDED");
        assertThat(mobileMoney.status(p.bidId, supplementId, p.sender.getFirebaseUid()).paymentStatus())
                .isEqualTo("REFUNDED");
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID bidId) {
    }

    private Parties parties() {
        UserEntity traveler = persistUser(true);
        UserEntity sender = persistUser(false);
        UUID bidId = persistAcceptedBid(persistAnnouncement(traveler.getId()), sender.getId());
        return new Parties(traveler, sender, bidId);
    }

    private UUID createSupplement(Parties p) {
        return supplementService.create(p.bidId, p.traveler.getFirebaseUid(), new CreateSupplementRequest(
                new BigDecimal("13000"), BidSupplementReason.GRID_ITEM, null, "Téléphone",
                "tracking/" + p.bidId + "/1_supplement.jpg")).id();
    }

    /** Demande acceptée, dépôt initié puis confirmé : supplément payé. */
    private UUID payDeposit(Parties p) throws Exception {
        UUID supplementId = createSupplement(p);
        paymentFlow.accept(p.bidId, supplementId, p.sender.getFirebaseUid());
        UUID depositId = mobileMoney.initiate(p.bidId, supplementId, p.sender.getFirebaseUid(), null, null)
                .deposit().id();
        operations.apply(depositId, PawapayOperationStatus.COMPLETED, null, null, "tx-" + depositId, null, "{}",
                PawapayOperationService.Source.CALLBACK);
        awaitStatus(supplementId, "PAID");
        return supplementId;
    }

    private UUID persistReleasedPrincipal(UUID bidId) {
        return tx.execute(status -> {
            PaymentEntity payment = new PaymentEntity();
            payment.setBidId(bidId);
            payment.setAmount(new BigDecimal("55000"));
            payment.setCommissionAmount(new BigDecimal("5000"));
            payment.setCurrency("XOF");
            payment.setRail(PaymentRail.PAWAPAY);
            payment.setStatus(PaymentStatus.RELEASED);
            payment.setEscrowReleasedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            entityManager.persist(payment);
            entityManager.flush();
            PawapayOperationEntity principalPayout = new PawapayOperationEntity(UUID.randomUUID(),
                    PawapayOperationKind.PAYOUT, payment.getId(), null, new BigDecimal("50000"), "XOF", "ORANGE_SEN",
                    "SN", PRINCIPAL_PAYEE);
            principalPayout.setStatus(PawapayOperationStatus.COMPLETED);
            entityManager.persist(principalPayout);
            return payment.getId();
        });
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

    private void awaitAudit(UUID supplementId, String action) throws InterruptedException {
        Supplier<Integer> count = () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?", Integer.class, supplementId, action);
        for (int i = 0; i < 100 && count.get() == 0; i++) {
            Thread.sleep(100);
        }
        assertThat(count.get()).isEqualTo(1);
    }

    private UUID awaitOperation(UUID supplementId, String kind) throws InterruptedException {
        Supplier<List<UUID>> ids = () -> jdbc.queryForList(
                "SELECT id FROM pawapay_operations WHERE bid_supplement_id = ? AND kind = ?", UUID.class, supplementId,
                kind);
        for (int i = 0; i < 100 && ids.get().isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(ids.get()).hasSize(1);
        return ids.get().get(0);
    }

    private UserEntity persistUser(boolean traveler) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("supplement-mm-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        if (traveler) {
            user.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
            user.setMobileMoneyCurrency("XOF");
            user.setMobileMoneyMsisdn(ACCOUNT_MSISDN);
            user.setMobileMoneyMsisdnMasked("+221 •••• 02");
            user.setMobileMoneyProvider("ORANGE_SEN");
            user.setMobileMoneyProviderList(List.of("ORANGE_SEN"));
            user.setMobileMoneyCountry("SN");
        }
        UserEntity saved = userRepository.saveAndFlush(user);
        if (traveler) {
            // Le compte de versement vit dans sa table ; le résumé de l'utilisateur n'en est que le reflet.
            MobileMoneyPayoutAccountEntity account = new MobileMoneyPayoutAccountEntity(saved.getId(), "ORANGE_SEN", "SN",
                    "XOF", ACCOUNT_MSISDN, "+221 •••• 02", java.time.Instant.now());
            account.setMigrated(true);
            accountRepository.saveAndFlush(account);
        }
        return saved;
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
            announcement.setPricePerKg(new BigDecimal("13000"));
            announcement.setCurrency("XOF");
            announcement.setTimezone("Africa/Dakar");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.MOBILE_MONEY));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    private UUID persistAcceptedBid(UUID announcementId, UUID senderId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
            bid.setCurrency("XOF");
            bid.setWeightKg(new BigDecimal("2"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setMobileMoneyPhone(PAYER);
            bid.setMobileMoneyCountryCode("SN");
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
