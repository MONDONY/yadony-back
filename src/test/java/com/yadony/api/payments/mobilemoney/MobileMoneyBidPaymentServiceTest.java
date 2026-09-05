package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
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

    private MobileMoneyBidPaymentService service;
    private UserEntity traveler;
    private UserEntity sender;
    private AnnouncementEntity announcement;
    private BidEntity bid;

    private static final PawapayProviderConfig.Limits OK =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "NONE", "PROVIDER_AUTH", "OPERATIONAL");

    @BeforeEach
    void setUp() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, userRepository, paymentRepository,
                operations, submission, client, pricing, firebaseContact, audit, events,
                new PawapayProperties(true, "https://x", "t", false, 30, "https://api.test", "yadony://bids/%s/mobile-money/awaiting",
                        new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO)));
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
        when(pricing.price(bid, announcement)).thenReturn(new PriceBreakdown(new BigDecimal("15000"), new BigDecimal("1800"), new BigDecimal("16800")));
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
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK, OK)));
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
        PawapayProviderConfig.Limits redirect = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "NONE", "REDIRECT_AUTH", "OPERATIONAL");
        when(client.activeConfiguration()).thenReturn(Map.of("WAVE_SEN", new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", redirect, null, null)));
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
        when(client.activeConfiguration()).thenReturn(Map.of("MTN_MOMO_CIV", new PawapayProviderConfig("MTN_MOMO_CIV", "CIV", "XOF", OK, OK, OK)));
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
        when(client.activeConfiguration()).thenReturn(Map.of("MTN_MOMO_CMR", new PawapayProviderConfig("MTN_MOMO_CMR", "CMR", "XAF", OK, OK, OK)));

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
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK, OK)));
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
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", OK, OK, OK)));

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
}
