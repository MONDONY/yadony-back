package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.PriceBreakdown;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import com.yadony.api.promo.PromoRedemptionEntity;
import com.yadony.api.promo.PromoService;
import com.yadony.api.voucher.CommissionVoucherService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class MobileMoneyBidPaymentServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayClient client;
    @Mock MobileMoneyBidPricing pricing;
    @Mock FirebaseContactService firebaseContact;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    // Ronde 1 : point 1 (rachat promo / consommation du bon), point 3 (transaction
    // indépendante pour l'audit d'un dépôt refusé).
    @Mock PromoService promoService;
    @Mock CommissionVoucherService voucherService;
    @Mock PlatformTransactionManager transactionManager;

    private MobileMoneyBidPaymentService service;
    private UserEntity traveler;
    private UserEntity sender;
    private AnnouncementEntity announcement;
    private BidEntity bid;

    private static final PawapayProviderConfig.Limits OK =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "OPERATIONAL");

    private static MobileMoneyBidPricing.Quote quote(PriceBreakdown price) {
        return new MobileMoneyBidPricing.Quote(price, false);
    }

    private static PawapayProperties enabledProps() {
        return new PawapayProperties(true, "https://x", "t", false, 30, "https://api.test",
                "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @BeforeEach
    void setUp() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, userRepository, paymentRepository,
                operations, submission, new PawapayProviderResolver(client), pricing, firebaseContact, audit, events,
                promoService, voucherService, transactionManager, enabledProps());
        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", UUID.randomUUID());
        traveler.setFirebaseUid("t-uid");
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        // Le compte de versement doit être dans la même devise que l'annonce (voir
        // acceptBid_travelerCurrencyMismatch_is422) — sans quoi le chemin heureux serait
        // rejeté par cette même garde, un compte sans devise ne correspondant à aucune annonce.
        traveler.setMobileMoneyCurrency("XOF");
        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", UUID.randomUUID());
        sender.setFirebaseUid("s-uid");
        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(traveler.getId());
        announcement.setCurrency("XOF");
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        // CapacityUnit n'a pas de constante KG (contrairement à ce que suppose le brief) : la
        // plus proche pour une capacité BORNÉE (nécessaire à acceptBid_capacityInsufficient_is409)
        // est KG_EXACT — « le voyageur saisit un nombre de kg exact [...] bornée comme les
        // valises », par opposition à KG_FREE qui est la seule valeur non bornée.
        announcement.setCapacityUnit(CapacityUnit.KG_EXACT);
        announcement.setAvailableKg(new BigDecimal("20"));
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        bid.setStatus(BidStatus.PENDING);
        bid.setWeightKg(new BigDecimal("5"));
        bid.setMobileMoneyPhone("221771234567");
    }

    private void stubLocks() {
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
    }

    // ── acceptBid ───────────────────────────────────────────────────────────

    @Test
    void acceptBid_reservesCapacity_createsPendingPayment_andPublishesMobileMoneyAcceptedEvent() {
        stubLocks();
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        when(pricing.price(bid, announcement)).thenReturn(quote(new PriceBreakdown(new BigDecimal("15000"), new BigDecimal("1800"), new BigDecimal("16800"))));
        when(paymentRepository.save(any())).thenAnswer(inv -> { PaymentEntity p = inv.getArgument(0); ReflectionTestUtils.setField(p, "id", UUID.randomUUID()); return p; });

        MobileMoneyPaymentStatusResponse r = service.acceptBid(bid.getId(), traveler.getId());

        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(29));
        assertThat(announcement.getAvailableKg()).isEqualByComparingTo("15");
        ArgumentCaptor<PaymentEntity> saved = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getRail()).isEqualTo(PaymentRail.PAWAPAY);
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("16800");
        assertThat(saved.getValue().getCommissionAmount()).isEqualByComparingTo("1800");
        assertThat(saved.getValue().getCurrency()).isEqualTo("XOF");
        assertThat(saved.getValue().getStripePaymentIntentId()).isNull();
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(BidAcceptedEvent.class)
                .extracting(e -> ((BidAcceptedEvent) e).isMobileMoney()).isEqualTo(true);
        assertThat(r.bidStatus()).isEqualTo("AWAITING_PAYMENT");
        assertThat(r.paymentStatus()).isEqualTo("PENDING");
        verify(audit).log(eq("BID"), eq(bid.getId()), eq("MM_BID_ACCEPTED_AWAITING_PAYMENT"), eq(traveler.getId()), any());
    }

    @Test
    void acceptBid_isIdempotent_whenAlreadyAwaitingWithPayment() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        stubLocks();
        PaymentEntity existing = new PaymentEntity();
        existing.setRail(PaymentRail.PAWAPAY);
        existing.setStatus(PaymentStatus.PENDING);
        existing.setAmount(new BigDecimal("16800"));
        existing.setCurrency("XOF");
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(existing));

        service.acceptBid(bid.getId(), traveler.getId());

        verify(paymentRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void acceptBid_notOwner_is403() {
        stubLocks();
        assertThatThrownBy(() -> service.acceptBid(bid.getId(), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("forbidden");
    }

    @Test
    void acceptBid_travelerLostAccount_is422() {
        stubLocks();
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.DISABLED);
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-account-required");
    }

    @Test
    void acceptBid_capacityInsufficient_is409() {
        stubLocks();
        announcement.setAvailableKg(new BigDecimal("2"));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("capacity-insufficient");
    }

    /**
     * Écart déclaré par rapport au cahier des charges (voir task-13-report.md) : le compte de
     * versement du voyageur peut avoir été désactivé puis réactivé dans une autre devise entre
     * la création du bid (garde posée tâche 12 dans {@code BidService.resolvePaymentMethodFor})
     * et cette acceptation. {@code acceptBid} est le dernier portail avant que l'argent bouge :
     * sans cette revérification, le dépôt réussirait et le versement serait rejeté à la
     * livraison — argent encaissé, voyageur impayable. Même code d'erreur que la garde de la
     * tâche 12, pour une seule et même cause métier.
     */
    @Test
    void acceptBid_travelerCurrencyMismatch_is422() {
        stubLocks();
        traveler.setMobileMoneyCurrency("XAF");
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-currency-mismatch");
        verify(paymentRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    // ── initiateDeposit ─────────────────────────────────────────────────────

    private PaymentEntity pendingPayment() {
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setBidId(bid.getId());
        p.setRail(PaymentRail.PAWAPAY);
        p.setStatus(PaymentStatus.PENDING);
        p.setAmount(new BigDecimal("16800"));
        p.setCommissionAmount(new BigDecimal("1800"));
        p.setCurrency("XOF");
        return p;
    }

    private PawapayOperationEntity op(UUID paymentId, PawapayOperationStatus status) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, paymentId, null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    @Test
    void initiateDeposit_submitsWithBidPhone_andReturnsOperation() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));
        PawapayOperationEntity accepted = op(payment.getId(), PawapayOperationStatus.ACCEPTED);
        when(submission.submitDeposit(eq(payment.getId()), eq("221771234567"), eq("ORANGE_SEN"), eq("SN"),
                eq(new BigDecimal("16800")), eq("XOF"), anyString(), any(), any())).thenReturn(accepted);

        MobileMoneyPaymentStatusResponse r = service.initiateDeposit(bid.getId(), sender.getId(), null);

        assertThat(r.deposit().status()).isEqualTo("ACCEPTED");
        assertThat(r.deposit().providerLabel()).isEqualTo("Orange Money");
        assertThat(r.deposit().msisdnMasked()).isEqualTo("+221 •••• 67");
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("MM_DEPOSIT_INITIATED"), eq(sender.getId()), any());
    }

    @Test
    void initiateDeposit_wave_passesReturnUrlsBuiltOnBidId() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "WAVE_SEN", "221771234567")));
        PawapayProviderConfig.Limits redirect = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "REDIRECT_AUTH", "OPERATIONAL");
        when(client.activeConfiguration()).thenReturn(Map.of("WAVE_SEN", new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", redirect, null)));
        when(submission.submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(op(payment.getId(), PawapayOperationStatus.ACCEPTED));

        service.initiateDeposit(bid.getId(), sender.getId(), null);

        verify(submission).submitDeposit(eq(payment.getId()), eq("221771234567"), eq("WAVE_SEN"), eq("SN"), any(), eq("XOF"),
                anyString(), eq("https://api.test/api/v1/pawapay/return/" + bid.getId() + "?outcome=success"),
                eq("https://api.test/api/v1/pawapay/return/" + bid.getId() + "?outcome=failed"));
    }

    @Test
    void initiateDeposit_secondCall_returnsTheLiveOperation_withoutSubmitting() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(op(payment.getId(), PawapayOperationStatus.PROCESSING)));

        MobileMoneyPaymentStatusResponse r = service.initiateDeposit(bid.getId(), sender.getId(), null);

        assertThat(r.deposit().status()).isEqualTo("PROCESSING");
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(client, never()).predictProvider(any());
    }

    @Test
    void initiateDeposit_afterFailedDeposit_submitsANewOne_withOverrideNumber() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(client.predictProvider("225070000000")).thenReturn(Optional.of(new PawapayProviderPrediction("CIV", "MTN_MOMO_CIV", "225070000000")));
        when(client.activeConfiguration()).thenReturn(Map.of("MTN_MOMO_CIV", new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF", OK, OK)));
        when(submission.submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(op(payment.getId(), PawapayOperationStatus.ACCEPTED));

        service.initiateDeposit(bid.getId(), sender.getId(), "+225 07 00 00 000");

        verify(submission).submitDeposit(eq(payment.getId()), eq("225070000000"), eq("MTN_MOMO_CIV"), eq("CI"), any(), any(), anyString(), any(), any());
        assertThat(bid.getMobileMoneyPhone()).as("le numéro corrigé est snapshoté").isEqualTo("225070000000");
    }

    @Test
    void initiateDeposit_currencyMismatch_is422() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("CMR", "MTN_MOMO_CMR", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("MTN_MOMO_CMR", new PawapayProviderConfig("MTN_MOMO_CMR", "CMR", "XAF", OK, OK)));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
    }

    @Test
    void initiateDeposit_submitRejected_is422WithReason() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider(any())).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));
        PawapayOperationEntity rejected = op(payment.getId(), PawapayOperationStatus.SUBMIT_REJECTED);
        rejected.setFailureMessage("Provider down");
        when(submission.submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(rejected);

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("Provider down")
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-deposit-rejected");
    }

    @Test
    void initiateDeposit_deadlinePassed_is422() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(pendingPayment()));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payment-expired");
    }

    @Test
    void initiateDeposit_notSender_is403_andPaymentNotPending_is409() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity escrow = pendingPayment();
        escrow.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(pendingPayment())).thenReturn(Optional.of(escrow));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), UUID.randomUUID(), null))
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("forbidden");
        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payment-not-pending");
    }

    /**
     * Écart déclaré (voir task-13-report.md) : {@code phoneOverride} est une entrée du client
     * et est déjà encadré, mais le numéro PRÉDIT PAR PAWAPAY (après un appel réseau réussi à
     * {@code predictProvider}) ne l'était pas dans le cahier des charges — même défaut que
     * celui déjà corrigé deux fois dans cette branche (borne {@code Msisdn.normalize} non
     * alignée avec l'origine de la donnée). Ce n'est pas une saisie de l'expéditeur : même
     * traitement que {@code MobileMoneyAccountService#activate} (502, pas 422).
     */
    @Test
    void initiateDeposit_predictedPhoneNormalizeFails_is502() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        // pawaPay renvoie un numéro hors bornes Msisdn (2 chiffres) : ce n'est pas l'expéditeur
        // qui l'a saisi, il n'a rien tapé de faux.
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "12")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Écart déclaré (voir task-13-report.md) : le numéro Firebase relu quand ni
     * {@code phoneOverride} ni {@code bid.mobileMoneyPhone} ne sont disponibles n'était pas
     * non plus encadré dans le cahier des charges. Un numéro Firebase hors bornes doit rester
     * un 422 (l'expéditeur doit indiquer un numéro exploitable), jamais un 500.
     */
    @Test
    void initiateDeposit_firebasePhoneInvalid_is422() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        bid.setMobileMoneyPhone(null);
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));
        when(firebaseContact.getContact(sender.getFirebaseUid())).thenReturn(new FirebaseContactService.Contact("12", null));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
        verify(client, never()).predictProvider(any());
    }

    // ── status ──────────────────────────────────────────────────────────────

    @Test
    void status_isVisibleToSenderAndTraveler_only() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());

        assertThat(service.status(bid.getId(), sender.getId()).paymentStatus()).isNull();
        assertThat(service.status(bid.getId(), traveler.getId()).bidStatus()).isEqualTo("AWAITING_PAYMENT");
        assertThatThrownBy(() -> service.status(bid.getId(), UUID.randomUUID()))
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("forbidden");
    }

    // ── Ronde 1, point 4 : l'interrupteur d'urgence coupe aussi accept/initiate ─────────────

    @Test
    void acceptBid_railDisabled_is422_beforeAnyRepositoryAccess() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, userRepository, paymentRepository,
                operations, submission, new PawapayProviderResolver(client), pricing, firebaseContact, audit, events,
                promoService, voucherService, transactionManager, disabledProps());

        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-disabled");
        verifyNoInteractions(bidRepository, announcementRepository);
    }

    private static PawapayProperties disabledProps() {
        return new PawapayProperties(false, "https://x", "t", false, 30, "https://api.test",
                "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /**
     * Ronde 2, point 3 (tranché par le coordinateur) : la garde a été déplacée APRÈS la
     * branche idempotente — elle bloque encore toute NOUVELLE soumission (rien n'a été
     * appelé côté pawaPay), mais seulement une fois établi qu'aucun deposit n'est déjà en vol.
     */
    @Test
    void initiateDeposit_railDisabled_blocksOnlyANewSubmission() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, userRepository, paymentRepository,
                operations, submission, new PawapayProviderResolver(client), pricing, firebaseContact, audit, events,
                promoService, voucherService, transactionManager, disabledProps());
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-disabled");
        verifyNoInteractions(submission, client);
    }

    /**
     * Ronde 2, point 3 : un dépôt déjà en vol reste consultable même rail coupé — relire une
     * opération existante n'engage aucun débit, l'interrupteur d'urgence ne doit donc jamais
     * bloquer ce chemin (sinon un expéditeur qui relance en pleine saisie de PIN, ou dont
     * l'app repolle simplement le statut, recevrait un 422 au lieu de son opération).
     */
    @Test
    void initiateDeposit_railDisabled_stillReturnsAnAlreadyLiveDeposit() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, userRepository, paymentRepository,
                operations, submission, new PawapayProviderResolver(client), pricing, firebaseContact, audit, events,
                promoService, voucherService, transactionManager, disabledProps());
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(payment.getId(), PawapayOperationStatus.PROCESSING)));

        MobileMoneyPaymentStatusResponse r = service.initiateDeposit(bid.getId(), sender.getId(), null);

        assertThat(r.deposit().status()).isEqualTo("PROCESSING");
        verifyNoInteractions(submission, client);
    }

    // ── Ronde 1, point 1 : rachat du promo et consommation du bon de parrainage ─────────────

    /**
     * Ordre exact exigé par la revue : après pricing.price (sinon la remise déjà figée dans
     * le taux disparaîtrait), avant la création du PaymentEntity — même position que le rail
     * espèces (PaymentService.createEscrow).
     */
    @Test
    void acceptBid_consumesSenderVoucher_afterPricing_beforePaymentCreation() {
        stubLocks();
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        when(pricing.price(bid, announcement)).thenReturn(quote(new PriceBreakdown(new BigDecimal("15000"), new BigDecimal("1800"), new BigDecimal("16800"))));
        when(paymentRepository.save(any())).thenAnswer(inv -> { PaymentEntity p = inv.getArgument(0); ReflectionTestUtils.setField(p, "id", UUID.randomUUID()); return p; });

        service.acceptBid(bid.getId(), traveler.getId());

        var inOrder = org.mockito.Mockito.inOrder(pricing, voucherService, paymentRepository);
        inOrder.verify(pricing).price(bid, announcement);
        inOrder.verify(voucherService).consume(sender.getId(), bid.getId());
        inOrder.verify(paymentRepository).save(any());
    }

    /**
     * Ronde 2, point 2 : {@code pricing} est mocké et ne pose donc jamais
     * {@code bid.commissionRate} tout seul — le stub doit le faire lui-même (via
     * {@code thenAnswer}), exactement comme le ferait la vraie
     * {@link MobileMoneyBidPricing#price}. Sans ça, le test appelait {@code redeem} avec
     * {@code null} (masqué par le matcher {@code any()} qui matche aussi null) sur une
     * colonne {@code NOT NULL} — le seul piège latent qui restait. La valeur EXACTE transmise
     * à {@code redeem} est maintenant assertée, pas seulement « un argument quelconque ».
     */
    @Test
    void acceptBid_redeemsPromoCode_whenStillValidAtAcceptance() {
        bid.setPromoCode("WELCOME10");
        stubLocks();
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        BigDecimal appliedRate = new BigDecimal("0.10");
        when(pricing.price(bid, announcement)).thenAnswer(inv -> {
            bid.setCommissionRate(appliedRate);
            return new MobileMoneyBidPricing.Quote(new PriceBreakdown(new BigDecimal("15000"), new BigDecimal("1500"), new BigDecimal("16500")), true);
        });
        UUID promoCodeId = UUID.randomUUID();
        PromoRedemptionEntity redemption = mock(PromoRedemptionEntity.class);
        when(redemption.getPromoCodeId()).thenReturn(promoCodeId);
        when(promoService.redeem(eq("WELCOME10"), eq(sender.getId()), eq(bid.getId()), eq(appliedRate))).thenReturn(redemption);
        when(paymentRepository.save(any())).thenAnswer(inv -> { PaymentEntity p = inv.getArgument(0); ReflectionTestUtils.setField(p, "id", UUID.randomUUID()); return p; });

        service.acceptBid(bid.getId(), traveler.getId());

        verify(promoService).redeem(eq("WELCOME10"), eq(sender.getId()), eq(bid.getId()), eq(appliedRate));
        assertThat(bid.getPromoCodeId()).isEqualTo(promoCodeId);
    }

    /** Même repli silencieux que MobileMoneyBidPricing : un promo devenu invalide entre la
     * création et l'acceptation ne doit ni faire échouer acceptBid, ni être racheté. */
    @Test
    void acceptBid_promoNoLongerValidAtAcceptance_doesNotRedeem() {
        bid.setPromoCode("EXPIRED");
        stubLocks();
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        when(pricing.price(bid, announcement)).thenReturn(quote(new PriceBreakdown(new BigDecimal("15000"), new BigDecimal("1800"), new BigDecimal("16800"))));
        when(paymentRepository.save(any())).thenAnswer(inv -> { PaymentEntity p = inv.getArgument(0); ReflectionTestUtils.setField(p, "id", UUID.randomUUID()); return p; });

        service.acceptBid(bid.getId(), traveler.getId());

        verify(promoService, never()).redeem(any(), any(), any(), any());
        verify(voucherService).consume(sender.getId(), bid.getId());
    }

    /**
     * Ronde 2, point 4 : renommé — l'ancien nom
     * ({@code acceptBid_secondEnvoiFromSameSender_getsFullRateBecauseVoucherAlreadyConsumed})
     * affirmait que ce test prouvait la disparition de la remise sur un second envoi.
     * {@code pricing} étant mocké, la commission « pleine » du bid 2 est dictée par SON
     * PROPRE stub, pas par une consommation réelle du bon : ce test ne peut pas établir
     * qu'un bon devient indisponible après consommation. Ce qu'il établit RÉELLEMENT, et qui
     * reste utile, c'est qu'{@link MobileMoneyBidPaymentService#acceptBid} appelle
     * {@code voucherService.consume(sender, bidId)} pour CHAQUE bid accepté, avec
     * l'identifiant DE CE bid comme clé — jamais une seule fois pour tous les envois d'un
     * même expéditeur. C'est cette fidélité (une clé par bid, jamais partagée ni omise) qui
     * rend opérant le filtre {@code consumedAt IS NULL} de
     * {@code CommissionVoucherService}/{@code CommissionRateResolver} (logique déjà testée
     * par {@code CommissionVoucherServiceTest}, pas reproduite ici). La preuve d'ORDRE (après
     * {@code pricing.price}, avant la création du paiement) est ailleurs :
     * {@link #acceptBid_consumesSenderVoucher_afterPricing_beforePaymentCreation()}.
     */
    @Test
    void acceptBid_consumesVoucher_withEachAcceptedBidsOwnIdAsKey() {
        stubLocks();
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        when(pricing.price(bid, announcement)).thenReturn(quote(new PriceBreakdown(new BigDecimal("15000"), new BigDecimal("900"), new BigDecimal("15900"))));
        when(paymentRepository.save(any())).thenAnswer(inv -> { PaymentEntity p = inv.getArgument(0); ReflectionTestUtils.setField(p, "id", UUID.randomUUID()); return p; });

        service.acceptBid(bid.getId(), traveler.getId());
        verify(voucherService).consume(sender.getId(), bid.getId());

        // Un second bid, même expéditeur/voyageur/annonce — sa propre clé, distincte de celle
        // du premier bid.
        BidEntity bid2 = new BidEntity();
        ReflectionTestUtils.setField(bid2, "id", UUID.randomUUID());
        bid2.setAnnouncementId(announcement.getId());
        bid2.setSenderId(sender.getId());
        bid2.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        bid2.setStatus(BidStatus.PENDING);
        bid2.setWeightKg(new BigDecimal("5"));
        bid2.setMobileMoneyPhone("221771234567");
        when(bidRepository.findByIdForUpdate(bid2.getId())).thenReturn(Optional.of(bid2));
        when(paymentRepository.findByBidId(bid2.getId())).thenReturn(Optional.empty());
        when(pricing.price(bid2, announcement)).thenReturn(quote(new PriceBreakdown(new BigDecimal("15000"), new BigDecimal("1800"), new BigDecimal("16800"))));

        service.acceptBid(bid2.getId(), traveler.getId());

        verify(voucherService).consume(sender.getId(), bid2.getId());
    }

    // ── Ronde 1, point 10 : le bilan idempotent ignore un paiement d'un autre rail ──────────

    @Test
    void acceptBid_idempotentBranch_ignoresPaymentOfAnotherRail() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        stubLocks();
        PaymentEntity stripePayment = new PaymentEntity();
        stripePayment.setRail(PaymentRail.STRIPE);
        stripePayment.setStatus(PaymentStatus.PENDING);
        stripePayment.setAmount(new BigDecimal("16800"));
        stripePayment.setCurrency("XOF");
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(stripePayment));

        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("invalid-status");
    }

    // ── Ronde 1, point 2 : predictProvider / activeConfiguration encadrés ───────────────────

    @Test
    void initiateDeposit_predictProviderNetworkFailure_is502() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenThrow(new RestClientException("pawaPay indisponible"));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_activeConfigurationNetworkFailure_is502() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenThrow(new RestClientException("pawaPay indisponible"));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── Ronde 1, point 9 : pays non convertible ─────────────────────────────────────────────

    @Test
    void initiateDeposit_unmappableCountry_is422() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        // "ZZZ" n'est un alpha-3 ISO d'aucun pays réel : PawapayCountries.toAlpha2 renvoie null.
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("ZZZ", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "ZZZ", "XOF", OK, OK)));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── Ronde 1, point 3 : l'audit d'un dépôt refusé survit au rollback ─────────────────────

    /**
     * Ronde 2, point 1 : la version précédente ne vérifiait qu'un appel à
     * {@code transactionManager.getTransaction(any())} — un {@code TransactionTemplate} resté
     * en propagation par défaut (REQUIRED, donc rejoignant la transaction d'initiateDeposit,
     * donc de nouveau effaçable par son rollback) aurait produit exactement le même appel et
     * laissé ce test vert. La propagation EXACTE demandée au gestionnaire est maintenant
     * capturée et assertée.
     */
    @Test
    void initiateDeposit_auditOfRejectedDeposit_usesItsOwnTransaction() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider(any())).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK)));
        PawapayOperationEntity rejected = op(payment.getId(), PawapayOperationStatus.SUBMIT_REJECTED);
        rejected.setFailureMessage("Provider down");
        when(submission.submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(rejected);

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class);

        // L'audit du dépôt refusé est bien écrit (comme avant), mais désormais via sa PROPRE
        // transaction (sollicitation explicite de transactionManager) — pas celle
        // d'initiateDeposit, vouée au rollback par le throw qui suit dans le code.
        ArgumentCaptor<org.springframework.transaction.TransactionDefinition> definition =
                ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("MM_DEPOSIT_INITIATED"), eq(sender.getId()), any());
    }

    // ── Branches d'erreur d'acceptBid ───────────────────────────────────────

    @Test
    void acceptBid_unknownBid_is404() {
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("bid-not-found");
    }

    @Test
    void acceptBid_bidNotPaidByMobileMoney_is422_beforeAnyPaymentRead() {
        stubLocks();
        bid.setPaymentMethod(PaymentMethod.CASH);

        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("invalid-payment-method");
        verifyNoInteractions(paymentRepository);
        assertThat(announcement.getAvailableKg()).as("aucune capacité réservée").isEqualByComparingTo("20");
    }

    @Test
    void acceptBid_announcementNoLongerAccepting_is409_withoutReservingCapacity() {
        stubLocks();
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        announcement.setStatus(AnnouncementStatus.CANCELLED);

        assertThatThrownBy(() -> service.acceptBid(bid.getId(), traveler.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("announcement-not-accepting");
        verify(paymentRepository, never()).save(any());
        assertThat(announcement.getAvailableKg()).isEqualByComparingTo("20");
    }

    // ── Branches d'erreur d'initiateDeposit (résolution du payeur) ──────────

    @Test
    void initiateDeposit_noProviderForNumber_is422_withoutReadingTheConfiguration() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("Aucun opérateur");
                });
        verify(client, never()).activeConfiguration();
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_providerClosedForDeposits_is422_namingTheProvider() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        PawapayProviderConfig.Limits closed =
                new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "CLOSED");
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", closed, OK)));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("Orange Money");
                });
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_amountAboveProviderDepositCap_is422() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        // Plafond deposit de l'opérateur (10 000) sous le montant du colis (16 800).
        PawapayProviderConfig.Limits capped =
                new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("10000"), "PROVIDER_AUTH", "OPERATIONAL");
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", capped, OK)));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("limites");
                });
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_invalidOverrideNumber_is422_beforeAnyPawapayCall() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), "pas un numéro"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("invalide");
                });
        verifyNoInteractions(client);
    }

    @Test
    void initiateDeposit_withoutAnyPayerNumber_is422_phoneRequired() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        bid.setMobileMoneyPhone(null);
        PaymentEntity payment = pendingPayment();
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));
        when(firebaseContact.getContact(sender.getFirebaseUid())).thenReturn(new FirebaseContactService.Contact(null, null));

        assertThatThrownBy(() -> service.initiateDeposit(bid.getId(), sender.getId(), null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-phone-required");
        verifyNoInteractions(client);
    }
}
