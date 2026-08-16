package com.yadony.api.payments.cash;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private final CommissionProperties props =
            new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24);

    private CashCommissionService service;

    @BeforeEach
    void setUp() {
        service = new CashCommissionService(props, userRepo, bidRepo, announcementRepo, events,
                walletService, walletTransactionRepository, auditService, commissionRateResolver,
                negotiationThreadRepository, stripeCashGateway, bidGridItemRepository, stubbedContacts());
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
}
