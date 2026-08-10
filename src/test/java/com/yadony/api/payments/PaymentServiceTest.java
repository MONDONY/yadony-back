package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.dto.ConnectAccountResponse;
import com.yadony.api.payments.dto.OnboardingLinkResponse;
import com.yadony.api.payments.dto.PaymentResponse;
import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import com.yadony.api.payments.currency.CurrencyMatchGuard;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UserBusinessPrefsRepository userBusinessPrefsRepository;

    PaymentService service;
    com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    com.yadony.api.promo.PromoService promoService;

    private final UUID senderId   = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId      = UUID.randomUUID();
    private final UUID annId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        commissionRateResolver = PaymentServiceTestFactory.stubbedResolver();
        promoService = org.mockito.Mockito.mock(com.yadony.api.promo.PromoService.class);
        service = new PaymentService(
                userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(com.yadony.api.common.stripe.AdminAlertService.class),
                commissionRateResolver, promoService, new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(),
                userBusinessPrefsRepository, new CurrencyMatchGuard()
);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertYadonyError(ThrowableAssert.ThrowingCallable callable, String expectedErrorCode) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo(expectedErrorCode);
    }

    private UserEntity buildUser(UUID id, String firebaseUid) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirebaseUid(firebaseUid);
        return u;
    }

    private BidEntity buildBid(BidStatus status) {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5.0));
        b.setStatus(status);
        return b;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        setId(ann, annId);
        ann.setTravelerId(travelerId);
        ann.setPricePerKg(BigDecimal.valueOf(5.0));
        return ann;
    }

    private PaymentEntity buildPayment(PaymentStatus status, String piId) {
        PaymentEntity p = new PaymentEntity();
        setId(p, UUID.randomUUID());
        p.setBidId(bidId);
        p.setStripePaymentIntentId(piId);
        p.setStatus(status);
        p.setAmount(new BigDecimal("25.00"));
        p.setCommissionAmount(new BigDecimal("3.00"));
        return p;
    }

    private UserBusinessPrefsEntity prefsWithCurrency(String currency) {
        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setUserId(senderId);
        prefs.setCurrencyCode(currency);
        return prefs;
    }

    private void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            Field f = null;
            while (clazz != null) {
                try { f = clazz.getDeclaredField("id"); break; }
                catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
            }
            if (f == null) throw new NoSuchFieldException("id not found in class hierarchy");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("Could not set id via reflection", e);
        }
    }

    // ── createEscrow ──────────────────────────────────────────────────────────

    @Test
    void createEscrow_bidNotFound_throwsNotFound() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        var request = mock(com.yadony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertYadonyError(() -> service.createEscrow(request, "uid-sender"), "bid-not-found");
    }

    @Test
    void createEscrow_bidNotBelongingToSender_throwsForbidden() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        bid.setSenderId(UUID.randomUUID()); // different sender
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        var request = mock(com.yadony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertYadonyError(() -> service.createEscrow(request, "uid-sender"), "forbidden");
    }

    @Test
    void createEscrow_rejectedBid_throwsUnprocessable() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid(BidStatus.REJECTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        var request = mock(com.yadony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertYadonyError(() -> service.createEscrow(request, "uid-sender"), "bid-not-payable");
    }

    @Test
    void createEscrow_alreadyInEscrow_throwsConflict() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        PaymentEntity existing = buildPayment(PaymentStatus.ESCROW, "pi_existing");
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));

        var request = mock(com.yadony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertYadonyError(() -> service.createEscrow(request, "uid-sender"), "payment-already-completed");
    }

    @Test
    void createEscrow_travelerNotOnboarded_throwsTravelerNotEligible() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        var request = mock(com.yadony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        Throwable thrown = catchThrowable(() -> service.createEscrow(request, "uid-sender"));
        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
    }

    @Test
    void createEscrow_clientAmountMismatch_throwsUnprocessable() {
        // SECURITE : le montant net est recalculé serveur (5 kg × 5 €/kg = 25,00 €,
        // pas de grid). Un totalNetEur client divergent (0,01 €) doit être rejeté
        // (amount-mismatch) — sinon l'expéditeur ferait sous-payer le voyageur.
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        traveler.setStripeAccountId("acct_ok");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        var request = mock(com.yadony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);
        when(request.getTotalNetEur()).thenReturn(new BigDecimal("0.01"));

        assertYadonyError(() -> service.createEscrow(request, "uid-sender"), "amount-mismatch");
    }

    // ── createConnectAccount ──────────────────────────────────────────────────

    @Test
    void createConnectAccount_userNotFound_throwsNotFound() {
        when(userRepository.findByFirebaseUid("uid-x")).thenReturn(Optional.empty());
        assertYadonyError(() -> service.createConnectAccount("uid-x"), "user-not-found");
    }

    @Test
    void createConnectAccount_alreadyHasAccount_returnsExisting() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_existing");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAcct = mock(Account.class);
            acctStatic.when(() -> Account.retrieve("acct_existing")).thenReturn(mockAcct);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");
            assertThat(resp.stripeAccountId()).isEqualTo("acct_existing");
        }
    }

    @Test
    void createConnectAccount_createsNewAccount_setsStatusPending() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setCountry("FR");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAcct = mock(Account.class);
            when(mockAcct.getId()).thenReturn("acct_new");
            acctStatic.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(mockAcct);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");
            assertThat(resp.stripeAccountId()).isEqualTo("acct_new");
            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
        }
    }

    // ── createOnboardingLink ──────────────────────────────────────────────────

    @Test
    void createOnboardingLink_noStripeAccount_throwsConflict() {
        UserEntity user = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        assertYadonyError(() -> service.createOnboardingLink("uid-sender"), "stripe-account-required");
    }

    @Test
    void createOnboardingLink_success_returnsUrl() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        try (MockedStatic<AccountLink> linkStatic = mockStatic(AccountLink.class)) {
            AccountLink link = mock(AccountLink.class);
            when(link.getUrl()).thenReturn("https://connect.stripe.com/onboarding");
            linkStatic.when(() -> AccountLink.create(any(AccountLinkCreateParams.class))).thenReturn(link);

            OnboardingLinkResponse resp = service.createOnboardingLink("uid-sender");
            assertThat(resp.url()).isEqualTo("https://connect.stripe.com/onboarding");
        }
    }

    @Test
    void createOnboardingLink_stripeAccountMissing_resetsAndThrowsConflict() throws Exception {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_deleted");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        try (MockedStatic<AccountLink> linkStatic = mockStatic(AccountLink.class)) {
            com.stripe.exception.InvalidRequestException missing =
                    new com.stripe.exception.InvalidRequestException("No such account",
                            "acct_deleted", null, "resource_missing", 404, null);
            linkStatic.when(() -> AccountLink.create(any(AccountLinkCreateParams.class)))
                    .thenThrow(missing);
            assertYadonyError(() -> service.createOnboardingLink("uid-sender"), "stripe-account-invalid");
            assertThat(user.getStripeAccountId()).isNull();
            assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.NOT_CREATED);
            verify(userRepository).save(user);
        }
    }

    // ── Webhook handlers (package-private, called directly) ──────────────────

    @Test
    void handleAccountUpdated_chargesEnabled_setsOnboarded() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(true);
        when(mockAccount.getPayoutsEnabled()).thenReturn(true);
        when(mockAccount.getId()).thenReturn("acct_123");

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleAccountUpdated(mockEvent);

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
        assertThat(user.getStripeOnboardingCompletedAt()).isNotNull();
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
        verify(eventPublisher).publishEvent(any(com.yadony.api.payments.events.StripeOnboardingCompletedEvent.class));
    }

    @Test
    void handleAccountUpdated_chargesEnabled_alreadyOnboarded_idempotent() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(true);
        when(mockAccount.getPayoutsEnabled()).thenReturn(true);
        when(mockAccount.getId()).thenReturn("acct_123");

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleAccountUpdated(mockEvent);

        verify(eventPublisher, never()).publishEvent(any(com.yadony.api.payments.events.StripeOnboardingCompletedEvent.class));
        verify(auditService, never()).log(any(), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
    }

    @Test
    void handleAccountUpdated_chargesDisabled_noDisabledReason_doesNothing() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_pending");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(false);
        when(mockAccount.getPayoutsEnabled()).thenReturn(false);
        when(mockAccount.getId()).thenReturn("acct_pending");
        when(mockAccount.getRequirements()).thenReturn(null);

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_pending")).thenReturn(Optional.of(user));

        service.handleAccountUpdated(mockEvent);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void handlePaymentEscrowActive_pendingPayment_movesToEscrow() {
        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_escrow");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_escrow");
        when(mockPi.getAmountCapturable()).thenReturn(2500L);

        Event mockEvent = buildEventWith("payment_intent.amount_capturable_updated", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_escrow")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handlePaymentEscrowActive(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(eventPublisher).publishEvent(any(PaymentEscrowReadyEvent.class));
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_ESCROW_ACTIVE"), any(), any());
    }

    @Test
    void handlePaymentEscrowActive_nonPendingPayment_doesNothing() {
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, "pi_already_escrow");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_already_escrow");

        Event mockEvent = buildEventWith("payment_intent.amount_capturable_updated", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_already_escrow")).thenReturn(Optional.of(payment));

        service.handlePaymentEscrowActive(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW); // unchanged
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void handlePaymentFailed_pendingPayment_marksAsFailed() {
        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_failed");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_failed");

        Event mockEvent = buildEventWith("payment_intent.payment_failed", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_failed")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handlePaymentFailed(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_FAILED"), any(), any());
    }

    @Test
    void handleChargeRefunded_marksAsRefunded() {
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, "pi_refund");

        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn("pi_refund");

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleChargeRefunded(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void handleChargeRefunded_alreadyRefunded_idempotent() {
        // Rejeu d'un charge.refunded sur un paiement déjà REFUNDED : le montant remboursé
        // (absolu, cumulé) est ré-enregistré à l'identique — aucun changement de statut,
        // aucun nouvel audit PAYMENT_REFUNDED (idempotence de la nouvelle machine à états).
        PaymentEntity payment = buildPayment(PaymentStatus.REFUNDED, "pi_already_refunded");
        payment.setRefundedAmount(new BigDecimal("30.00"));

        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn("pi_already_refunded");
        when(mockCharge.getAmount()).thenReturn(3000L);
        when(mockCharge.getAmountRefunded()).thenReturn(3000L);

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);
        when(paymentRepository.findByStripePaymentIntentId("pi_already_refunded")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleChargeRefunded(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED); // inchangé
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("30.00");
        verify(auditService, never()).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void handleChargeRefunded_noPaymentIntentId_ignores() {
        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn(null);

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);

        service.handleChargeRefunded(mockEvent);

        verifyNoInteractions(paymentRepository);
    }

    // ── getPaymentStatusForBid ────────────────────────────────────────────────

    @Test
    void getPaymentStatusForBid_noPayment_returnsEmpty() {
        UserEntity caller = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(caller));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertThat(service.getPaymentStatusForBid(bidId, "uid-sender")).isEmpty();
    }

    @Test
    void getPaymentStatusForBid_withPayment_returnsResponse() {
        UserEntity caller = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(caller));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, null);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        Optional<PaymentResponse> resp = service.getPaymentStatusForBid(bidId, "uid-sender");

        assertThat(resp).isPresent();
        assertThat(resp.get().getStatus()).isEqualTo("ESCROW");
        assertThat(resp.get().getAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    void getPaymentStatusForBid_negotiationFlow_fallsBackToThreadPayment() {
        // Régression : un envoi issu d'une négociation (trajet dédié/lié payé Stripe)
        // a son paiement escrow rattaché au thread, avec bid_id NULL. La recherche par
        // bidId échoue → on doit retomber sur le paiement du thread, sinon l'écran
        // affiche à tort « Payer mon envoi » alors que c'est déjà payé.
        UUID threadId = UUID.randomUUID();
        UserEntity caller = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(caller));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        bid.setLinkedNegotiationThreadId(threadId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, "pi_123");
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(payment));

        Optional<PaymentResponse> resp = service.getPaymentStatusForBid(bidId, "uid-sender");

        assertThat(resp).isPresent();
        assertThat(resp.get().getStatus()).isEqualTo("ESCROW");
    }

    // ── confirmBidPayment ─────────────────────────────────────────────────────

    @Test
    void confirmBidPayment_promotes_when_PI_requires_capture() {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_test");
        AnnouncementEntity ann = buildAnnouncement();
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Dakar");
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setFirstName("Marie");

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidRepository.findByPaymentIntentId("pi_test")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            piStatic.when(() -> PaymentIntent.retrieve("pi_test")).thenReturn(pi);

            boolean result = service.confirmBidPayment(bidId);

            assertThat(result).isTrue();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.PAYMENT_ESCROWED);
            verify(eventPublisher).publishEvent(any(com.yadony.api.matching.events.BidCreatedEvent.class));
        }
    }

    @Test
    void confirmBidPayment_idempotent_when_already_PAYMENT_ESCROWED() {
        BidEntity bid = buildBid(BidStatus.PAYMENT_ESCROWED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        boolean result = service.confirmBidPayment(bidId);

        assertThat(result).isTrue();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void confirmBidPayment_returns_false_when_bid_in_other_status() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        boolean result = service.confirmBidPayment(bidId);

        assertThat(result).isFalse();
    }

    @Test
    void confirmBidPayment_returns_false_when_PI_status_unknown() {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_test");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_payment_method");
            piStatic.when(() -> PaymentIntent.retrieve("pi_test")).thenReturn(pi);

            boolean result = service.confirmBidPayment(bidId);

            assertThat(result).isFalse();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        }
    }

    @Test
    void confirmBidPayment_throws_when_bid_not_found() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        assertYadonyError(() -> service.confirmBidPayment(bidId), "bid-not-found");
    }

    @Test
    void confirmBidPayment_returns_false_when_no_payment_intent_id() {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        // paymentIntentId left null
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        boolean result = service.confirmBidPayment(bidId);

        assertThat(result).isFalse();
    }

    @Test
    void confirmBidPayment_throws_502_when_stripe_fails() throws StripeException {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_test");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            StripeException ex = mock(StripeException.class);
            when(ex.getMessage()).thenReturn("network down");
            piStatic.when(() -> PaymentIntent.retrieve("pi_test")).thenThrow(ex);

            assertYadonyError(() -> service.confirmBidPayment(bidId), "stripe-error");
        }
    }

    @Test
    void handlePaymentEscrowActive_deserializerEmpty_fallbackToApiSetsEscrow() {
        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_fallback");

        Event event = mock(Event.class);
        lenient().when(event.getId()).thenReturn("evt_test_fallback");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.getRawJson()).thenReturn("{\"id\":\"pi_fallback\"}");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_fallback");
            when(mockPi.getAmountCapturable()).thenReturn(8400L);
            when(mockPi.getLatestCharge()).thenReturn(null);
            when(mockPi.getMetadata()).thenReturn(new java.util.HashMap<>());
            piStatic.when(() -> PaymentIntent.retrieve("pi_fallback")).thenReturn(mockPi);

            when(paymentRepository.findByStripePaymentIntentId("pi_fallback")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bidRepository.findByPaymentIntentId("pi_fallback")).thenReturn(Optional.empty());

            service.handlePaymentEscrowActive(event);
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(eventPublisher).publishEvent(any(PaymentEscrowReadyEvent.class));
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_ESCROW_ACTIVE"), any(), any());
    }

    @Test
    void confirmBidPayment_requiresCapture_alsoSetsPaymentToEscrow() {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_test2");
        AnnouncementEntity ann = buildAnnouncement();
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Dakar");
        UserEntity sender = buildUser(senderId, "uid-sender");

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidRepository.findByPaymentIntentId("pi_test2")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_test2");
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getId()).thenReturn("pi_test2");
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.getLatestCharge()).thenReturn("ch_test2");
            piStatic.when(() -> PaymentIntent.retrieve("pi_test2")).thenReturn(pi);

            boolean result = service.confirmBidPayment(bidId);

            assertThat(result).isTrue();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.PAYMENT_ESCROWED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
            assertThat(payment.getStripeChargeId()).isEqualTo("ch_test2");
            verify(paymentRepository, atLeastOnce()).save(payment);
            verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_ESCROW_ACTIVE"), any(), any());
        }
    }

    // ── createNegotiationEscrow (Model B) ─────────────────────────────────────

    @Test
    void createNegotiationEscrow_usesServerCadOneToOne_withoutFxQuote() throws Exception {
        UUID threadId = UUID.randomUUID();

        // sender and traveler
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing"); // évite Customer.create (statique non mocké)
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(userBusinessPrefsRepository.findById(senderId))
                .thenReturn(Optional.of(prefsWithCurrency("CAD")));
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // net = 35 €, rate = 0.12 → gross = 39.20 €, commission = 4.20 €
        BigDecimal netAmount = new BigDecimal("35.00");

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {

            // ensureCardPaymentsCapability calls Account.retrieve
            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            // PaymentIntent.create — capture the params
            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_negotiation_123");
            when(mockPi.getClientSecret()).thenReturn("pi_negotiation_123_secret");

            java.util.concurrent.atomic.AtomicReference<PaymentIntentCreateParams> capturedParams =
                    new java.util.concurrent.atomic.AtomicReference<>();
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenAnswer(inv -> {
                        capturedParams.set(inv.getArgument(0));
                        return mockPi;
                    });

            PaymentResponse resp = service.createNegotiationEscrow(
                    threadId, senderId, travelerId, netAmount, null, "CAD");

            // Modèle "separate charges and transfers" : on encaisse le gross sur la
            // plateforme, SANS application_fee_amount ni transfer_data (Stripe rejette
            // application_fee_amount sans transfer_data). La commission est versée
            // implicitement via le Transfer(net) à la livraison (DeliveryEventListener).
            PaymentIntentCreateParams params = capturedParams.get();
            assertThat(params).isNotNull();
            assertThat(params.getAmount()).isEqualTo(3920L);               // 39.20 CAD, sans conversion
            assertThat(params.getCurrency()).isEqualTo("cad");
            assertThat(params.getExtraParams()).isNullOrEmpty();
            assertThat(params.getMetadata()).doesNotContainKeys("fx_quote_id", "fx_exchange_rate");
            assertThat(params.getApplicationFeeAmount()).isNull();         // PAS de fee ici
            assertThat(params.getTransferData()).isNull();                 // pas de destination charge
            assertThat(params.getOnBehalfOf()).isNull();                   // retiré (incompatible PayPal)
            assertThat(params.getPaymentMethodTypes()).containsExactly("card", "paypal");

            // L'entité paiement enregistre toujours gross + commission : la commission
            // sert au calcul du Transfer(net = gross - commission) à la livraison.
            assertThat(resp.getAmount()).isEqualByComparingTo(new BigDecimal("39.20"));
            assertThat(resp.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("4.20"));
            assertThat(resp.getCurrency()).isEqualTo("cad");

            var paymentCaptor = org.mockito.ArgumentCaptor.forClass(PaymentEntity.class);
            verify(paymentRepository).save(paymentCaptor.capture());
            PaymentEntity saved = paymentCaptor.getValue();
            assertThat(saved.getAmount()).isEqualByComparingTo("39.20");
            assertThat(saved.getCommissionAmount()).isEqualByComparingTo("4.20");
            assertThat(saved.getCurrency()).isEqualTo("cad");
            assertThat(saved.getStripeFxQuoteId()).isNull();
            assertThat(saved.getFxExchangeRate()).isNull();
            assertThat(saved.getFxQuoteExpiresAt()).isNull();
        }
    }

    @Test
    void createNegotiationEscrow_currencyMismatchFailsBeforePaymentResume() {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userBusinessPrefsRepository.findById(senderId))
                .thenReturn(Optional.of(prefsWithCurrency("CAD")));

        assertYadonyError(
                () -> service.createNegotiationEscrow(
                        threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR"),
                "currency-mismatch");

        verify(userRepository, never()).findById(travelerId);
        verifyNoInteractions(paymentRepository, auditService);
    }

    @Test
    void createNegotiationEscrow_attachesExistingSenderCustomer() throws Exception {
        // Cartes enregistrées dans la PaymentSheet native : Stripe exige que le customer
        // propriétaire du payment_method soit attaché au PaymentIntent. Sans lui, payer
        // une négociation avec une carte enregistrée est rejeté par Stripe.
        UUID threadId = UUID.randomUUID();

        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {

            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_negotiation_123");
            when(mockPi.getClientSecret()).thenReturn("pi_negotiation_123_secret");

            java.util.concurrent.atomic.AtomicReference<PaymentIntentCreateParams> capturedParams =
                    new java.util.concurrent.atomic.AtomicReference<>();
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenAnswer(inv -> {
                        capturedParams.set(inv.getArgument(0));
                        return mockPi;
                    });

            service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR");

            assertThat(capturedParams.get().getCustomer()).isEqualTo("cus_existing");
            // Customer déjà existant → jamais recréé
            verify(userRepository, never()).save(sender);
        }
    }

    @Test
    void createNegotiationEscrow_noSenderCustomer_createsAndPersistsCustomer() throws Exception {
        UUID threadId = UUID.randomUUID();

        UserEntity sender = buildUser(senderId, "uid-sender");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<com.stripe.model.Customer> custStatic =
                     mockStatic(com.stripe.model.Customer.class)) {

            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            com.stripe.model.Customer mockCustomer = mock(com.stripe.model.Customer.class);
            when(mockCustomer.getId()).thenReturn("cus_created");
            custStatic.when(() -> com.stripe.model.Customer.create(
                            any(com.stripe.param.CustomerCreateParams.class)))
                    .thenReturn(mockCustomer);

            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_negotiation_123");
            when(mockPi.getClientSecret()).thenReturn("pi_negotiation_123_secret");

            java.util.concurrent.atomic.AtomicReference<PaymentIntentCreateParams> capturedParams =
                    new java.util.concurrent.atomic.AtomicReference<>();
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenAnswer(inv -> {
                        capturedParams.set(inv.getArgument(0));
                        return mockPi;
                    });

            service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR");

            assertThat(capturedParams.get().getCustomer()).isEqualTo("cus_created");
            assertThat(sender.getStripeCustomerId()).isEqualTo("cus_created");
            verify(userRepository).save(sender);
        }
    }

    @Test
    void createNegotiationEscrow_travelerNotOnboarded_throwsTravelerNotEligible() {
        UUID threadId = UUID.randomUUID();

        UserEntity sender = buildUser(senderId, "uid-sender");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() ->
                service.createNegotiationEscrow(
                        threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR"));
        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
    }

    @Test
    void createNegotiationEscrow_alreadyInEscrow_throwsConflict() {
        UUID threadId = UUID.randomUUID();

        UserEntity sender = buildUser(senderId, "uid-sender");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");

        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentEntity existing = new PaymentEntity();
        setId(existing, UUID.randomUUID());
        existing.setNegotiationThreadId(threadId);
        existing.setStripePaymentIntentId("pi_old");
        existing.setAmount(new BigDecimal("39.20"));
        existing.setCommissionAmount(new BigDecimal("4.20"));
        existing.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(existing));

        assertYadonyError(() ->
                service.createNegotiationEscrow(
                        threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR"),
                "payment-already-completed");
    }

    @Test
    void createNegotiationEscrow_existingCanceledPayment_recyclesRowNot409() throws Exception {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing"); // évite Customer.create (statique non mocké)
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentEntity stale = new PaymentEntity();
        setId(stale, UUID.randomUUID());
        stale.setNegotiationThreadId(threadId);
        stale.setStripePaymentIntentId("pi_old_canceled");
        stale.setStatus(PaymentStatus.CANCELLED);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(stale));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_fresh");
            when(mockPi.getClientSecret()).thenReturn("pi_fresh_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(mockPi);

            service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR");

            // Same row recycled (no duplicate insert under the UNIQUE constraint),
            // now pointing at the fresh PaymentIntent and back to PENDING.
            assertThat(stale.getStripePaymentIntentId()).isEqualTo("pi_fresh");
            assertThat(stale.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentRepository).save(stale);
        }
    }

    @Test
    void createNegotiationEscrow_existingPendingCanceledPi_recyclesRow() throws Exception {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing"); // évite Customer.create (statique non mocké)
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentEntity stale = new PaymentEntity();
        setId(stale, UUID.randomUUID());
        stale.setNegotiationThreadId(threadId);
        stale.setStripePaymentIntentId("pi_old");
        stale.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(stale));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            // The existing PENDING PaymentIntent is canceled (stale) → recycle the row.
            PaymentIntent canceledPi = mock(PaymentIntent.class);
            when(canceledPi.getStatus()).thenReturn("canceled");
            piStatic.when(() -> PaymentIntent.retrieve("pi_old")).thenReturn(canceledPi);

            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent freshPi = mock(PaymentIntent.class);
            when(freshPi.getId()).thenReturn("pi_fresh2");
            when(freshPi.getClientSecret()).thenReturn("pi_fresh2_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(freshPi);

            service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR");

            assertThat(stale.getStripePaymentIntentId()).isEqualTo("pi_fresh2");
            assertThat(stale.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentRepository).save(stale);
        }
    }

    @Test
    void createNegotiationEscrow_pendingLegacyStripeFxAmountAndCurrency_cancelsAndRecycles() throws Exception {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(userBusinessPrefsRepository.findById(senderId))
                .thenReturn(Optional.of(prefsWithCurrency("CAD")));

        PaymentEntity legacy = new PaymentEntity();
        setId(legacy, UUID.randomUUID());
        legacy.setNegotiationThreadId(threadId);
        legacy.setStripePaymentIntentId("pi_legacy_fx");
        legacy.setAmount(new BigDecimal("39.20"));
        legacy.setCommissionAmount(new BigDecimal("4.20"));
        legacy.setCurrency("CAD");
        legacy.setStripeFxQuoteId("fxq_legacy");
        legacy.setFxExchangeRate(new BigDecimal("0.6800000000"));
        legacy.setFxQuoteExpiresAt(java.time.Instant.parse("2026-08-09T10:00:00Z"));
        legacy.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(legacy));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent legacyPi = mock(PaymentIntent.class);
            when(legacyPi.getStatus()).thenReturn("requires_payment_method");
            when(legacyPi.getAmount()).thenReturn(2666L);
            when(legacyPi.getCurrency()).thenReturn("eur");
            when(legacyPi.cancel(any(PaymentIntentCancelParams.class))).thenReturn(legacyPi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_legacy_fx")).thenReturn(legacyPi);

            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent freshPi = mock(PaymentIntent.class);
            when(freshPi.getId()).thenReturn("pi_fresh_cad");
            when(freshPi.getClientSecret()).thenReturn("pi_fresh_cad_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(freshPi);

            PaymentResponse response = service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), null, "CAD");

            assertThat(response.getStripePaymentIntentId()).isEqualTo("pi_fresh_cad");
            verify(legacyPi).cancel(any(PaymentIntentCancelParams.class));
            verify(paymentRepository).save(legacy);
            assertThat(legacy.getStripePaymentIntentId()).isEqualTo("pi_fresh_cad");
            assertThat(legacy.getStripeFxQuoteId()).isNull();
            assertThat(legacy.getFxExchangeRate()).isNull();
            assertThat(legacy.getFxQuoteExpiresAt()).isNull();
        }
    }

    @Test
    void createNegotiationEscrow_existingPendingRequiresActionPi_cancelsAndRecycles() throws Exception {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing"); // évite Customer.create (statique non mocké)
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentEntity stale = new PaymentEntity();
        setId(stale, UUID.randomUUID());
        stale.setNegotiationThreadId(threadId);
        stale.setStripePaymentIntentId("pi_stuck");
        stale.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(stale));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            // The existing PENDING PaymentIntent is stuck in requires_action (abandoned
            // 3DS/PayPal redirect) → it must be canceled on Stripe and the row recycled,
            // never treated as an already-completed payment (409).
            PaymentIntent stuckPi = mock(PaymentIntent.class);
            when(stuckPi.getStatus()).thenReturn("requires_action");
            piStatic.when(() -> PaymentIntent.retrieve("pi_stuck")).thenReturn(stuckPi);

            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent freshPi = mock(PaymentIntent.class);
            when(freshPi.getId()).thenReturn("pi_fresh3");
            when(freshPi.getClientSecret()).thenReturn("pi_fresh3_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(freshPi);

            service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR");

            verify(stuckPi).cancel(any(PaymentIntentCancelParams.class));
            assertThat(stale.getStripePaymentIntentId()).isEqualTo("pi_fresh3");
            assertThat(stale.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentRepository).save(stale);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"XOF", "XAF"})
    void createNegotiationEscrow_zeroDecimalCurrency_auditsNormalizedGrossCommissionAndCurrency(
            String serverCurrency) throws Exception {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(userBusinessPrefsRepository.findById(senderId))
                .thenReturn(Optional.of(prefsWithCurrency(serverCurrency)));
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent paymentIntent = mock(PaymentIntent.class);
            when(paymentIntent.getId()).thenReturn("pi_zero_decimal_" + serverCurrency.toLowerCase());
            when(paymentIntent.getClientSecret()).thenReturn("pi_zero_decimal_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(paymentIntent);

            service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("26.25"), null, serverCurrency);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).log(
                    eq("PAYMENT"), any(), eq("NEGOTIATION_ESCROW_CREATED"), eq(senderId),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("amount", new BigDecimal("29"))
                    .containsEntry("commission", new BigDecimal("3"))
                    .containsEntry("currency", serverCurrency.toLowerCase());
        }
    }

    @Test
    void createNegotiationEscrow_validPromo_reducesGrossAndReportsAppliedRate() throws Exception {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Base 0.12 (stubbedResolver) ; promo WELCOME6 retranche 6 points → 0.06.
        when(commissionRateResolver.resolve(eq(travelerId), eq(senderId), eq("WELCOME6")))
                .thenReturn(new BigDecimal("0.06"));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_promo_1");
            when(mockPi.getClientSecret()).thenReturn("pi_promo_1_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(mockPi);

            // net = 35 € ; rate promo 0.06 → gross = 37.10, commission = 2.10 (vs 39.20/4.20 sans promo).
            PaymentResponse resp = service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), "WELCOME6", "EUR");

            assertThat(resp.getAmount()).isEqualByComparingTo(new BigDecimal("37.10"));
            assertThat(resp.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("2.10"));
            assertThat(resp.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.06"));
            assertThat(resp.isPromoApplied()).isTrue();
        }
    }

    @Test
    void createNegotiationEscrow_invalidPromo_fallsBackSilentlyToBaseRate() throws Exception {
        UUID threadId = UUID.randomUUID();
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setStripeCustomerId("cus_existing");
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(commissionRateResolver.resolve(eq(travelerId), eq(senderId), eq("EXPIRED")))
                .thenThrow(new com.yadony.api.common.YadonyBusinessException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code promo a expiré"));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            Account mockAccount = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAccount.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve("acct_traveler")).thenReturn(mockAccount);

            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_promo_2");
            when(mockPi.getClientSecret()).thenReturn("pi_promo_2_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(mockPi);

            // Promo expiré → jamais bloquant : repli sur le taux de base (0.12), le
            // paiement continue au lieu de faire échouer l'expéditeur au checkout.
            PaymentResponse resp = service.createNegotiationEscrow(
                    threadId, senderId, travelerId, new BigDecimal("35.00"), "EXPIRED", "EUR");

            assertThat(resp.getAmount()).isEqualByComparingTo(new BigDecimal("39.20"));
            assertThat(resp.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.12"));
            assertThat(resp.isPromoApplied()).isFalse();
        }
    }

    // ── Helper to build a mocked Stripe Event with deserialized object ─────────

    private Event buildEventWith(String type, Object stripeObj) {
        Event event = mock(Event.class);
        lenient().when(event.getType()).thenReturn(type);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of((com.stripe.model.StripeObject) stripeObj));
        return event;
    }
}
