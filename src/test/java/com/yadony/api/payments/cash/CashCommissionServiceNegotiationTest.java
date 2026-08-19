package com.yadony.api.payments.cash;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashCommissionServiceNegotiationTest {

    /**
     * Coordonnées de test servies par Firebase — elles ne sont plus stockées en base
     * (cf. {@link FirebaseContactService}).
     */
    private static FirebaseContactService stubbedContacts() {
        var svc = mock(FirebaseContactService.class);
        lenient().when(svc.getContact(any()))
                .thenReturn(new FirebaseContactService.Contact("+33600000000", "test@yadony.app"));
        return svc;
    }

    @Mock private UserRepository userRepo;
    @Mock private BidRepository bidRepo;
    @Mock private AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher events;
    @Mock private WalletService walletService;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private AuditService auditService;
    @Mock private CommissionRateResolver commissionRateResolver;
    @Mock private NegotiationThreadRepository negotiationThreadRepository;
    @Mock private StripeCashGateway stripeCashGateway;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private com.yadony.api.voucher.CommissionVoucherService voucherService;

    private final CommissionProperties props =
            new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24);

    private CashCommissionService service;

    @BeforeEach
    void setUp() {
        service = new CashCommissionService(props, userRepo, bidRepo, announcementRepo, events,
                walletService, walletTransactionRepository, auditService, commissionRateResolver,
                negotiationThreadRepository, stripeCashGateway, bidGridItemRepository, stubbedContacts(),
                voucherService);
    }

    // --- helpers ---

    private NegotiationThreadEntity threadWithId(UUID id) {
        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        ReflectionTestUtils.setField(thread, "id", id);
        return thread;
    }

    private UserEntity travelerWithCard(UUID id, boolean withCard) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setStripeCustomerId("cus_test");
        if (withCard) {
            u.setCommissionPaymentMethodId("pm_test");
        }
        return u;
    }

    // Le portefeuille couvre la commission : débit immédiat, thread marqué CHARGED/WALLET.
    @Test
    void settleNegotiationCommission_walletCoversIt_debitsWalletAndMarksCharged() {
        // given un thread CASH de 100.00 EUR, taux 5 %, solde 20.00 EUR
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));
        when(walletService.getBalance(travelerId, "EUR")).thenReturn(new BigDecimal("20.00"));

        // when settleNegotiationCommission(..., WALLET_FIRST)
        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST);

        // then status == ACCEPTED, walletService.debit appelé une fois avec 5.00,
        //      thread.commissionStatus == "CHARGED", thread.commissionChargedVia == "WALLET"
        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        verify(walletService).debit(eq(travelerId), eq("EUR"), eq(new BigDecimal("5.00")),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(threadId.toString()),
                eq("nego_commission_wallet_" + threadId));
        assertThat(thread.getCommissionStatus()).isEqualTo("CHARGED");
        assertThat(thread.getCommissionChargedVia()).isEqualTo("WALLET");
        verify(negotiationThreadRepository).save(thread);
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_CHARGED"),
                eq(travelerId), any());
        verifyNoInteractions(stripeCashGateway);
    }

    // Solde court en WALLET_FIRST : on ne touche JAMAIS la carte automatiquement.
    @Test
    void settleNegotiationCommission_walletShort_returnsInsufficientWithoutChargingCard() {
        // given solde 1.00 EUR pour une commission de 5.00 EUR, voyageur avec carte enregistrée
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        UserEntity traveler = travelerWithCard(travelerId, true);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));
        when(walletService.getBalance(travelerId, "EUR")).thenReturn(new BigDecimal("1.00"));
        when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));

        // when settleNegotiationCommission(..., WALLET_FIRST)
        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST);

        // then status == INSUFFICIENT_WALLET, requiredCommission == 5.00,
        //      availableBalance == 1.00, hasCard == true,
        //      verifyNoInteractions(stripeCashGateway),
        //      thread.commissionStatus reste null (ni CHARGED ni FAILED)
        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.INSUFFICIENT_WALLET);
        assertThat(response.requiredCommission()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(response.availableBalance()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(response.hasCard()).isTrue();
        verifyNoInteractions(stripeCashGateway);
        assertThat(thread.getCommissionStatus()).isNull();
        verify(walletService, never()).debit(any(), any(), any(), any(), any(), any());
        verify(negotiationThreadRepository, never()).save(any());
    }

    // Choix explicite de la carte par le voyageur après un solde insuffisant.
    @Test
    void settleNegotiationCommission_cardSource_chargesCardAndMarksCharged() throws StripeException {
        // given CommissionSource.CARD, voyageur avec commissionPaymentMethodId,
        //       PaymentIntent Stripe qui revient "succeeded"
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        UserEntity traveler = travelerWithCard(travelerId, true);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));
        when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        when(pi.getId()).thenReturn("pi_nego_test");
        when(stripeCashGateway.createPaymentIntent(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(pi);

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.CARD);

        // then status == ACCEPTED, thread.commissionChargedVia == "CARD",
        //      thread.commissionPaymentIntentId renseigné
        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        assertThat(thread.getCommissionStatus()).isEqualTo("CHARGED");
        assertThat(thread.getCommissionChargedVia()).isEqualTo("CARD");
        assertThat(thread.getCommissionPaymentIntentId()).isEqualTo("pi_nego_test");
        verifyNoInteractions(walletService);
        verify(negotiationThreadRepository).save(thread);
    }

    // 3DS requis : le voyageur règle lui-même depuis son téléphone (contrairement au
    // flux classique où l'expéditeur déclenche à l'insu du voyageur) → REQUIRES_3DS,
    // pas un échec définitif. Le PaymentIntent est persisté pour la confirmation à venir.
    @Test
    void settleNegotiationCommission_cardSourceRequires3ds_returnsRequires3ds() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        UserEntity traveler = travelerWithCard(travelerId, true);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));
        when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("requires_action");
        when(pi.getId()).thenReturn("pi_nego_3ds");
        when(pi.getClientSecret()).thenReturn("pi_nego_3ds_secret");
        when(stripeCashGateway.createPaymentIntent(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(pi);

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.CARD);

        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.REQUIRES_3DS);
        assertThat(response.clientSecret()).isEqualTo("pi_nego_3ds_secret");
        assertThat(response.paymentIntentId()).isEqualTo("pi_nego_3ds");
        assertThat(thread.getCommissionStatus()).isEqualTo("REQUIRES_3DS");
        assertThat(thread.getCommissionPaymentIntentId()).isEqualTo("pi_nego_3ds");
        verifyNoInteractions(walletService);
        verify(negotiationThreadRepository).save(thread);
    }

    // Carte demandée mais aucune carte enregistrée : échec explicite, pas de NPE.
    @Test
    void settleNegotiationCommission_cardSourceWithoutCard_returnsFailed() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        UserEntity traveler = travelerWithCard(travelerId, false);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));
        when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.CARD);

        // then status == FAILED, error non nul, aucun appel Stripe
        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.FAILED);
        assertThat(response.error()).isNotNull();
        verifyNoInteractions(stripeCashGateway);
        verifyNoInteractions(walletService);
        assertThat(thread.getCommissionStatus()).isNull();
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_FAILED"),
                eq(travelerId), eq(java.util.Map.of("reason", "no-commission-card")));
    }

    // Idempotence : rejouer un règlement déjà effectué ne redébite pas.
    @Test
    void settleNegotiationCommission_alreadyCharged_isIdempotent() {
        // given thread.commissionStatus == "CHARGED"
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setCommissionStatus("CHARGED");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST);

        // then status == ACCEPTED, verifyNoInteractions(walletService)
        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        verifyNoInteractions(walletService);
        verifyNoInteractions(stripeCashGateway);
        verify(commissionRateResolver, never()).resolve(any(), any());
        verify(negotiationThreadRepository, never()).save(any());
    }

    // Commission nulle ou négative (prix négocié à 0) : rien à prélever, succès.
    @Test
    void settleNegotiationCommission_zeroCommission_returnsAcceptedWithoutDebit() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, BigDecimal.ZERO, CommissionSource.WALLET_FIRST);

        // then status == ACCEPTED, verify(walletService, never()).debit(...)
        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        verify(walletService, never()).debit(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(stripeCashGateway);
        assertThat(thread.getCommissionStatus()).isEqualTo("CHARGED");
        verify(negotiationThreadRepository).save(thread);
    }

    // Revue task 5, critique 4 : un échec carte (CardException) doit incrémenter le
    // compteur de réessai — sinon la clé d'idempotence Stripe reste figée pour
    // toujours et un nouveau règlement rejouerait indéfiniment le PaymentIntent mort.
    @Test
    void settleNegotiationCommission_cardDeclined_incrementsRetryCountForNextAttempt() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        UserEntity traveler = travelerWithCard(travelerId, true);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));
        when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(stripeCashGateway.createPaymentIntent(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new CardException("Card declined", null, "card_declined", null, null, null, null, null));

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.CARD);

        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.FAILED);
        assertThat(thread.getCommissionStatus()).isEqualTo("FAILED");
        assertThat(thread.getCommissionRetryCount()).isEqualTo(1);
        verify(negotiationThreadRepository).save(thread);
    }

    // Le discriminant de réessai doit produire une clé d'idempotence Stripe DIFFÉRENTE
    // à la tentative suivante — sinon Stripe rejouerait la réponse morte du premier
    // essai (ex. "requires_action") au lieu de retenter un débit réel, et le voyageur
    // ne pourrait plus jamais régler sa commission après un premier échec.
    @Test
    void settleNegotiationCommission_retryAfterCardDecline_usesFreshIdempotencyKey() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        UserEntity traveler = travelerWithCard(travelerId, true);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));
        when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentIntent successPi = mock(PaymentIntent.class);
        when(successPi.getStatus()).thenReturn("succeeded");
        when(successPi.getId()).thenReturn("pi_nego_retry");

        ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        when(stripeCashGateway.createPaymentIntent(any(PaymentIntentCreateParams.class), optsCaptor.capture()))
                .thenThrow(new CardException("Card declined", null, "card_declined", null, null, null, null, null))
                .thenReturn(successPi);

        // Premier essai : carte refusée, le compteur de réessai s'incrémente.
        AcceptBidResponse first = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.CARD);
        assertThat(first.status()).isEqualTo(AcceptanceStatusDto.FAILED);

        // Second essai : le voyageur relance le règlement (nouvelle carte, ou retry).
        AcceptBidResponse second = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), CommissionSource.CARD);
        assertThat(second.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);

        List<RequestOptions> capturedOpts = optsCaptor.getAllValues();
        assertThat(capturedOpts).hasSize(2);
        assertThat(capturedOpts.get(0).getIdempotencyKey())
                .isNotEqualTo(capturedOpts.get(1).getIdempotencyKey());
        assertThat(capturedOpts.get(0).getIdempotencyKey()).endsWith("_v0");
        assertThat(capturedOpts.get(1).getIdempotencyKey()).endsWith("_v1");
    }

    // --- confirmNegotiationCommissionAcceptance() : relecture post-3DS ---

    @Test
    void confirmNegotiationCommissionAcceptance_stripeSucceeded_marksChargedAndReturnsOk() throws StripeException {
        // given un thread REQUIRES_3DS avec un PaymentIntent qui revient "succeeded"
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setTravelerId(travelerId);
        thread.setCurrency("EUR");
        thread.setCommissionStatus("REQUIRES_3DS");
        thread.setCommissionPaymentIntentId("pi_nego_3ds");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        when(pi.getAmount()).thenReturn(500L);
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_3ds")).thenReturn(pi);

        // when confirmNegotiationCommissionAcceptance(threadId, travelerId)
        ConfirmAcceptanceResponse response =
                service.confirmNegotiationCommissionAcceptance(threadId, travelerId);

        // then accepted() == true, thread.commissionStatus == CHARGED,
        //      thread.commissionChargedVia == CARD
        assertThat(response.accepted()).isTrue();
        assertThat(thread.getCommissionStatus()).isEqualTo("CHARGED");
        assertThat(thread.getCommissionChargedVia()).isEqualTo("CARD");
        verify(negotiationThreadRepository).save(thread);
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_CHARGED"),
                eq(travelerId), any());
    }

    // Idempotence : rejouer une confirmation déjà aboutie ne relit jamais Stripe.
    @Test
    void confirmNegotiationCommissionAcceptance_alreadyCharged_isIdempotent() {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setTravelerId(travelerId);
        thread.setCommissionStatus("CHARGED");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));

        ConfirmAcceptanceResponse response =
                service.confirmNegotiationCommissionAcceptance(threadId, travelerId);

        assertThat(response.accepted()).isTrue();
        verifyNoInteractions(stripeCashGateway);
        verify(negotiationThreadRepository, never()).save(any());
    }

    // Ownership : seul le voyageur du thread peut confirmer sa propre commission.
    @Test
    void confirmNegotiationCommissionAcceptance_notOwner_throwsForbidden() {
        UUID travelerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setTravelerId(travelerId);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));

        assertThatThrownBy(() -> service.confirmNegotiationCommissionAcceptance(threadId, otherUserId))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        verifyNoInteractions(stripeCashGateway);
    }

    // Aucun PaymentIntent à confirmer (settleCommission jamais appelé côté carte) : échec propre.
    @Test
    void confirmNegotiationCommissionAcceptance_noPaymentIntent_returnsFail() {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setTravelerId(travelerId);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));

        ConfirmAcceptanceResponse response =
                service.confirmNegotiationCommissionAcceptance(threadId, travelerId);

        assertThat(response.accepted()).isFalse();
        assertThat(response.error()).isNotNull();
        verifyNoInteractions(stripeCashGateway);
    }

    // La 3DS n'a toujours pas abouti (état encore requires_action, ou refusée) :
    // le thread passe FAILED, la réponse porte l'échec — jamais de scellement.
    @Test
    void confirmNegotiationCommissionAcceptance_stripeStillNotSucceeded_marksFailedAndReturnsFail() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setTravelerId(travelerId);
        thread.setCommissionStatus("REQUIRES_3DS");
        thread.setCommissionPaymentIntentId("pi_nego_3ds");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("requires_payment_method");
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_3ds")).thenReturn(pi);

        ConfirmAcceptanceResponse response =
                service.confirmNegotiationCommissionAcceptance(threadId, travelerId);

        // Revue task 5, critiques 4 et 5 : un compteur de réessai incrémenté (sinon
        // un nouveau règlement rejoue la même clé Stripe morte) ET une entrée
        // audit_log CASH_COMMISSION_FAILED exploitable par le support — avant cette
        // revue, un échec de confirmation ne laissait AUCUNE trace.
        assertThat(response.accepted()).isFalse();
        assertThat(thread.getCommissionStatus()).isEqualTo("FAILED");
        assertThat(thread.getCommissionRetryCount()).isEqualTo(1);
        verify(negotiationThreadRepository).save(thread);
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_FAILED"),
                eq(travelerId), eq(java.util.Map.of("reason", "card-status-requires_payment_method")));
        // Le PaymentIntent abandonné doit être annulé chez Stripe. Sans cela, le
        // compteur de réessai autoriserait un second PaymentIntent pendant que le
        // premier reste vivant : si la banque finalisait le challenge après coup,
        // ce débit ne serait ni scellé, ni remboursé, ni tracé.
        verify(stripeCashGateway).cancelPaymentIntent("pi_nego_3ds");
    }

    // Re-revue task 5 : l'annulation du PaymentIntent abandonné ne doit jamais
    // empêcher le voyageur de retenter. Un PaymentIntent déjà annulé ou expiré
    // fait lever Stripe — le règlement suivant doit rester possible.
    @Test
    void confirmNegotiationCommissionAcceptance_cancelFails_stillMarksFailedAndIncrementsRetry()
            throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setTravelerId(travelerId);
        thread.setCommissionStatus("REQUIRES_3DS");
        thread.setCommissionPaymentIntentId("pi_nego_dead");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("requires_action");
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_dead")).thenReturn(pi);
        when(stripeCashGateway.cancelPaymentIntent("pi_nego_dead")).thenThrow(mock(StripeException.class));

        ConfirmAcceptanceResponse response =
                service.confirmNegotiationCommissionAcceptance(threadId, travelerId);

        assertThat(response.accepted()).isFalse();
        assertThat(thread.getCommissionStatus()).isEqualTo("FAILED");
        assertThat(thread.getCommissionRetryCount()).isEqualTo(1);
        verify(negotiationThreadRepository).save(thread);
    }

    /**
     * Garde-fou de propagation transactionnelle, pas un test de comportement.
     *
     * <p>L'appelant ({@code NegotiationService.confirmCommission}) lève un 409
     * immédiatement après ce remboursement, pour dire au voyageur que sa place a
     * été prise. En propagation {@code REQUIRED}, ce rollback effacerait le statut
     * {@code REFUNDED} et l'entrée d'audit, alors que le remboursement Stripe est
     * un appel HTTP externe déjà parti : il resterait un remboursement sans trace,
     * et sur la branche d'échec, plus rien pour déclencher le remboursement manuel.
     * Aucun test de comportement ne peut voir cette régression (les mocks Mockito
     * n'ouvrent pas de transaction), d'où cette assertion sur l'annotation.
     */
    @Test
    void refundNegotiationCommissionIfCharged_runsInItsOwnTransaction() throws NoSuchMethodException {
        Transactional tx = CashCommissionService.class
                .getMethod("refundNegotiationCommissionIfCharged", UUID.class, UUID.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation())
                .as("le remboursement doit survivre au rollback du 409 de l'appelant")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    // Erreur Stripe transitoire à la relecture : échec propre, jamais d'exception qui remonte.
    @Test
    void confirmNegotiationCommissionAcceptance_stripeErrorOnRetrieve_returnsFail() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setTravelerId(travelerId);
        thread.setCommissionPaymentIntentId("pi_nego_3ds");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_3ds")).thenThrow(mock(StripeException.class));

        ConfirmAcceptanceResponse response =
                service.confirmNegotiationCommissionAcceptance(threadId, travelerId);

        assertThat(response.accepted()).isFalse();
        verify(negotiationThreadRepository, never()).save(any());
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_FAILED"),
                eq(travelerId), eq(java.util.Map.of("reason", "stripe-error")));
    }

    // --- refundNegotiationCommissionIfCharged() : place prise pendant la 3DS ---
    // Revue task 5, critique 2 : la 3DS est asynchrone, un concurrent a pu sceller
    // pendant que le voyageur l'authentifiait. Si Stripe confirme malgré tout un
    // débit réussi, Yadony ne doit jamais garder cet argent sans contrepartie.

    @Test
    void refundNegotiationCommissionIfCharged_stripeSucceeded_refundsAndMarksRefunded() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setCurrency("EUR");
        thread.setCommissionPaymentIntentId("pi_nego_lost");
        thread.setCommissionStatus("REQUIRES_3DS");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        when(pi.getAmount()).thenReturn(500L);
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_lost")).thenReturn(pi);

        boolean refunded = service.refundNegotiationCommissionIfCharged(threadId, travelerId);

        assertThat(refunded).isTrue();
        assertThat(thread.getCommissionStatus()).isEqualTo("REFUNDED");
        verify(negotiationThreadRepository).save(thread);
        verify(stripeCashGateway).createRefund(any(RefundCreateParams.class), any(RequestOptions.class));
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_REFUNDED_SPOT_TAKEN"),
                eq(travelerId), argThat(map ->
                        "pi_nego_lost".equals(map.get("paymentIntentId")) && "5.00".equals(map.get("amount"))));
    }

    @Test
    void refundNegotiationCommissionIfCharged_noPaymentIntent_returnsFalseWithoutStripeCall() {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));

        boolean refunded = service.refundNegotiationCommissionIfCharged(threadId, travelerId);

        assertThat(refunded).isFalse();
        verifyNoInteractions(stripeCashGateway);
        verify(negotiationThreadRepository, never()).save(any());
    }

    // Idempotence : un remboursement déjà effectué ne rappelle jamais Stripe.
    @Test
    void refundNegotiationCommissionIfCharged_alreadyRefunded_isIdempotent() {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setCommissionStatus("REFUNDED");
        thread.setCommissionPaymentIntentId("pi_nego_lost");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));

        boolean refunded = service.refundNegotiationCommissionIfCharged(threadId, travelerId);

        assertThat(refunded).isFalse();
        verifyNoInteractions(stripeCashGateway);
    }

    @Test
    void refundNegotiationCommissionIfCharged_stripeNotSucceeded_returnsFalseWithoutRefund() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setCommissionPaymentIntentId("pi_nego_lost");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("requires_action");
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_lost")).thenReturn(pi);

        boolean refunded = service.refundNegotiationCommissionIfCharged(threadId, travelerId);

        assertThat(refunded).isFalse();
        verify(stripeCashGateway, never()).createRefund(any(), any());
        verify(negotiationThreadRepository, never()).save(any());
    }

    @Test
    void refundNegotiationCommissionIfCharged_stripeReadError_returnsFalseWithAudit() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setCommissionPaymentIntentId("pi_nego_lost");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_lost")).thenThrow(mock(StripeException.class));

        boolean refunded = service.refundNegotiationCommissionIfCharged(threadId, travelerId);

        assertThat(refunded).isFalse();
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_REFUND_CHECK_FAILED"),
                eq(travelerId), any());
        verify(negotiationThreadRepository, never()).save(any());
    }

    // Le remboursement automatique échoue côté Stripe : le voyageur a bien été
    // débité, il faut une trace exploitable pour un remboursement manuel — jamais
    // d'exception qui remonte.
    @Test
    void refundNegotiationCommissionIfCharged_stripeRefundFails_marksRefundFailedWithAudit() throws StripeException {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = threadWithId(threadId);
        thread.setCurrency("EUR");
        thread.setCommissionPaymentIntentId("pi_nego_lost");

        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        when(pi.getAmount()).thenReturn(500L);
        when(stripeCashGateway.retrievePaymentIntent("pi_nego_lost")).thenReturn(pi);
        when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(mock(StripeException.class));

        boolean refunded = service.refundNegotiationCommissionIfCharged(threadId, travelerId);

        assertThat(refunded).isFalse();
        assertThat(thread.getCommissionStatus()).isEqualTo("REFUND_FAILED");
        verify(negotiationThreadRepository).save(thread);
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_REFUND_FAILED"),
                eq(travelerId), any());
    }
}
