package com.yadony.api.payments.cash;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionResult;
import com.yadony.api.common.commission.ThreadCommissionPricing;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.promo.PromoDiscount;
import com.yadony.api.promo.PromoService;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rail espèces sur une grille de commission figée (spec §4.2, §4.4) : le bid et le fil de
 * demande prélèvent au voyageur la commission de la grille, minimum de la devise compris, et
 * un code promo que le minimum rend sans effet n'est ni verrouillé ni racheté. Sans grille, le
 * calcul hérité (taux figé et plancher {@code yadony.commission.minimum-amount}) reste intact.
 */
@ExtendWith(MockitoExtension.class)
class CashCommissionServiceGridTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    private static final String CODE = "MOINS5";

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
    @Mock private CommissionVoucherService voucherService;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private PromoService promoService;
    @Mock private CommissionGridService commissionGridService;
    @Mock private ThreadCommissionPricing threadCommissionPricing;

    private final CommissionProperties props =
            new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24);

    private CashCommissionService service;

    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        stubBidParties();
        FirebaseContactService contacts = mock(FirebaseContactService.class);
        lenient().when(contacts.getContact(any()))
                .thenReturn(new FirebaseContactService.Contact("+33600000000", "test@yadony.app"));
        lenient().when(bidGridItemRepository.findByBidId(any())).thenReturn(List.of());
        // Règle de repli du code promo : celle du résolveur (CommissionRateResolverTest), exécutée
        // pour de vrai sur le mock ; les bouchons de reductions(...) restent la source des réductions.
        lenient().when(commissionRateResolver.reductionsWithPromoFallback(any(), any(), any(), any())).thenCallRealMethod();
        lenient().when(commissionRateResolver.reductionsForBid(any(), any(), any(), isNull(), any())).thenCallRealMethod();
        lenient().when(activeCurrencyResolver.resolve(any())).thenReturn("EUR");
        lenient().when(commissionGridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
        FixedRateConversions.stubRoundedConversion(exchangeRateService);
        service = new CashCommissionService(props, userRepo, bidRepo, announcementRepo, events,
                walletService, walletTransactionRepository, auditService, commissionRateResolver,
                negotiationThreadRepository, stripeCashGateway, bidGridItemRepository, contacts,
                voucherService, activeCurrencyResolver,
                new WalletCommissionCollector(walletService, exchangeRateService, org.mockito.Mockito.mock(com.yadony.api.payments.currency.ExchangeRateFreshness.class)), TestMessages.resolver(),
                promoService);
        ReflectionTestUtils.setField(service, "commissionGridService", commissionGridService);
        ReflectionTestUtils.setField(service, "threadCommissionPricing", threadCommissionPricing);
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    // --- helpers ---

    /** Bid espèces de {@code weightKg} kg sur un trajet à {@code pricePerKg}, grille figée ou non. */
    private BidEntity bid(String weightKg, UUID gridId) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal(weightKg));
        bid.setStatus(BidStatus.PENDING);
        bid.setCommissionGridId(gridId);
        return bid;
    }

    private AnnouncementEntity announcement(String pricePerKg, String currency) {
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", UUID.randomUUID());
        ann.setTravelerId(travelerId);
        ann.setCurrency(currency);
        ann.setPricePerKg(new BigDecimal(pricePerKg));
        ann.setAvailableKg(new BigDecimal("50"));
        ann.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        return ann;
    }

    private void stubReductions(BidEntity bid, String promoCode, CommissionReductions reductions) {
        lenient().when(commissionRateResolver.reductions(travelerId, senderId, promoCode,
                promoCode == null ? null : senderId, bid.getId())).thenReturn(reductions);
    }

    private void stubAcceptance(BidEntity bid, AnnouncementEntity ann) {
        bid.setAnnouncementId(ann.getId());
        when(bidRepo.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepo.findByIdForUpdate(ann.getId())).thenReturn(Optional.of(ann));
        when(walletService.getBalanceForUpdate(travelerId, "EUR")).thenReturn(new BigDecimal("100"));
        lenient().when(walletTransactionRepository.existsByUserIdAndBidIdAndType(eq(travelerId), any(), any()))
                .thenReturn(false);
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        lenient().when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    /** Devis du fil servi par {@link ThreadCommissionPricing} (testé à part). */
    private void stubThreadPrice(UUID threadId, String net, String currency, String commission) {
        BigDecimal n = new BigDecimal(net);
        BigDecimal c = new BigDecimal(commission);
        when(threadCommissionPricing.price(GRID_7_6_5.id(), n, currency, travelerId, senderId, threadId))
                .thenReturn(new CommissionResult(n, c, n.add(c), BigDecimal.ZERO, false, false));
    }

    private NegotiationThreadEntity thread(UUID threadId, String currency, UUID gridId) {
        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        ReflectionTestUtils.setField(thread, "id", threadId);
        thread.setCurrency(currency);
        thread.setCommissionGridId(gridId);
        return thread;
    }

    // --- bid ---

    @Test
    void quoteBidCommission_grid_net20_140() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        stubReductions(bid, null, CommissionReductions.NONE);

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("20.00", "EUR"));

        // Tranche 1 de la grille 7/6/5 : 20,00 € × 7 % = 1,40 €, au-dessus du minimum.
        assertThat(quote.commission()).isEqualByComparingTo("1.40");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.07");
        verify(commissionRateResolver, never()).resolve(any(), any(), any(), any(), any());
    }

    @Test
    void quoteBidCommission_grid_net5_minimum100() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        stubReductions(bid, null, CommissionReductions.NONE);

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("5.00", "EUR"));

        // 0,35 € de grille relevés au minimum EUR de la grille (1,00 €). Le taux enregistré
        // (20 %) est informatif : les montants font foi.
        assertThat(quote.commission()).isEqualByComparingTo("1.00");
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.2");
    }

    @Test
    void quoteBidCommission_gridXof_isWholeFrancsWithGridMinimum() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        bid.setCurrency("XOF");
        stubReductions(bid, null, CommissionReductions.NONE);

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("5000", "XOF"));

        // Minimum XOF de la grille (650), pas le plancher hérité converti (656).
        assertThat(quote.commission()).isEqualByComparingTo("650");
        assertThat(quote.commission().scale()).isZero();
    }

    @Test
    void quoteBidCommission_gridBelowMinimumRateCappedUnderOne() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        stubReductions(bid, null, CommissionReductions.NONE);

        // 0,50 € de net : le minimum (1,00 €) fait 200 %, borné à 0,999999 (CHECK V118).
        service.quoteBidCommission(bid, announcement("0.50", "EUR"));

        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.999999");
    }

    @Test
    void quoteBidCommission_gridWithNonPositiveBase_isRejected() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        bid.setWeightKg(null);

        assertThatThrownBy(() -> service.quoteBidCommission(bid, announcement("20.00", "EUR")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo("invalid-amount");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @Test
    void quoteBidCommission_gridPromoEffective_reportsAppliedAndEffective() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        bid.setPromoCode(CODE);
        stubReductions(bid, CODE, new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("20.00", "EUR"));

        // 7 % − 5 points = 2 % → 0,40 €, relevé au minimum 1,00 € : moins que 1,40 € sans code.
        assertThat(quote.commission()).isEqualByComparingTo("1.00");
        assertThat(quote.promoApplied()).isTrue();
        assertThat(quote.promoEffective()).isTrue();
    }

    @Test
    void quoteBidCommission_gridInvalidPromo_fallsBackWithoutCode() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        bid.setPromoCode(CODE);
        when(commissionRateResolver.reductions(travelerId, senderId, CODE, senderId, bid.getId()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-limit-reached", "Promo Limit Reached", "limite"));
        stubReductions(bid, null, CommissionReductions.NONE);

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("20.00", "EUR"));

        assertThat(quote.commission()).isEqualByComparingTo("1.40");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
    }

    @Test
    void quoteBidCommission_gridRedeemedPromo_isNotRevalidated() {
        // Code déjà verrouillé pour ce bid (bindAppliedPromo) : un nouveau devis, après le verrou
        // ou le rachat, ne le revalide pas, sinon il échouerait sur sa propre utilisation et la
        // commission prélevée perdrait la remise affichée.
        BidEntity bid = bid("1", GRID_7_6_5.id());
        bid.setPromoCode(CODE);
        UUID promoCodeId = UUID.randomUUID();
        bid.setPromoCodeId(promoCodeId);
        when(commissionRateResolver.reductionsForBid(travelerId, senderId, CODE, promoCodeId, bid.getId()))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("100.00", "EUR"));

        // 100 € × (7 % − 5 points) = 2,00 € au lieu de 7,00 €.
        assertThat(quote.commission()).isEqualByComparingTo("2.00");
        assertThat(quote.promoEffective()).isTrue();
        verify(commissionRateResolver, never()).reductions(any(), any(), eq(CODE), any(), any());
        verify(commissionRateResolver, never()).reductionsWithPromoFallback(any(), any(), any(), any());
    }

    @Test
    void quoteBidCommission_gridSenderVoucher_appliedAfterMinimum() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        stubReductions(bid, null, new CommissionReductions(null, null, new BigDecimal("0.5")));

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("5.00", "EUR"));

        // Bon ×0,5 après le minimum : 1,00 € devient 0,50 € (vecteur §4.6).
        assertThat(quote.commission()).isEqualByComparingTo("0.50");
    }

    @Test
    void quoteBidCommission_gridNegotiatedNet_usesFrozenReductionsWithoutPromo() {
        BidEntity bid = bid("10", GRID_7_6_5.id());
        bid.setPromoCode(CODE);
        bid.setNegotiatedNetEur(new BigDecimal("100.00"));
        bid.setCommissionRate(new BigDecimal("0.07"));
        bid.setCommissionOverrideRate(new BigDecimal("0.04"));

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("50.00", "EUR"));

        // Taux spécial figé à l'ouverture du fil : 100 € × 4 % = 4,00 € (vecteur §4.6),
        // ni le barème de l'annonce (500 €) ni le code promo ni le résolveur du jour.
        assertThat(quote.commission()).isEqualByComparingTo("4.00");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.07");
        verifyNoInteractions(commissionRateResolver);
    }

    @Test
    void quoteBidCommission_gridNegotiatedGrossAndNet_isTheExactDifference() {
        BidEntity bid = bid("10", GRID_7_6_5.id());
        bid.setNegotiatedGrossEur(new BigDecimal("21.40"));
        bid.setNegotiatedNetEur(new BigDecimal("20.00"));

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("50.00", "EUR"));

        assertThat(quote.commission()).isEqualByComparingTo("1.40");
        verifyNoInteractions(commissionGridService, commissionRateResolver);
    }

    @Test
    void quoteBidCommission_noGrid_legacyFloorUnchanged() {
        BidEntity bid = bid("1", null);
        when(commissionRateResolver.resolve(travelerId, senderId, null, null, bid.getId()))
                .thenReturn(new BigDecimal("0.12"));

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("5.00", "EUR"));

        // 5 € × 12 % = 0,60 € relevé au plancher yadony.commission.minimum-amount (1,00 €).
        assertThat(quote.commission()).isEqualByComparingTo("1.00");
        assertThat(quote.promoApplied()).isFalse();
        assertThat(quote.promoEffective()).isFalse();
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.12");
        verifyNoInteractions(commissionGridService);
    }

    @Test
    void quoteBidCommission_noGrid_validPromo_effectiveMirrorsApplied() {
        BidEntity bid = bid("5", null);
        bid.setPromoCode(CODE);
        when(commissionRateResolver.resolve(travelerId, senderId, CODE, senderId, bid.getId()))
                .thenReturn(new BigDecimal("0.07"));

        CashCommissionService.BidCommissionQuote quote =
                service.quoteBidCommission(bid, announcement("20.00", "EUR"));

        assertThat(quote.commission()).isEqualByComparingTo("7.00");
        assertThat(quote.promoApplied()).isTrue();
        assertThat(quote.promoEffective()).isTrue();
        verifyNoInteractions(commissionGridService);
    }

    @Test
    void acceptCashBid_promoIneffective_notLocked() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        bid.setPromoCode(CODE);
        AnnouncementEntity ann = announcement("5.00", "EUR");
        stubAcceptance(bid, ann);
        // Net 5 € : 0,35 € de grille, 0,10 € avec le code, tous deux relevés au minimum 1,00 €.
        stubReductions(bid, CODE, new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, CommissionSource.WALLET_FIRST);

        assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        verify(walletService).debit(eq(travelerId), eq("EUR"), argThat(a -> a.compareTo(new BigDecimal("1.00")) == 0),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), any());
        verify(promoService, never()).lockForRedemption(any(), any(), any());
        verify(promoService, never()).recordGrantedRedemption(any(), any(), any(), any());
        assertThat(bid.getPromoCodeId()).isNull();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
    }

    @Test
    void acceptCashBid_promoEffective_lockedAndRedeemedAtStorableRate() {
        BidEntity bid = bid("1", GRID_7_6_5.id());
        bid.setPromoCode(CODE);
        AnnouncementEntity ann = announcement("20.00", "EUR");
        stubAcceptance(bid, ann);
        stubReductions(bid, CODE, new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));
        UUID promoId = UUID.randomUUID();
        when(promoService.lockForRedemption(CODE, senderId, bid.getId())).thenReturn(promoId);

        AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, CommissionSource.WALLET_FIRST);

        assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        verify(walletService).debit(eq(travelerId), eq("EUR"), argThat(a -> a.compareTo(new BigDecimal("1.00")) == 0),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), any());
        verify(promoService).lockForRedemption(CODE, senderId, bid.getId());
        // Taux enregistré : 1,00 / 20,00 = 5 %, celui du devis borné, jamais net × taux.
        verify(promoService).recordGrantedRedemption(eq(CODE), eq(senderId), eq(bid.getId()),
                argThat(r -> r.compareTo(new BigDecimal("0.05")) == 0));
    }

    // --- fil de demande ---

    @Test
    void settleNegotiationCommission_xofGrid_isWholeFrancsWithMinimum() {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = thread(threadId, "XOF", GRID_7_6_5.id());
        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        // Devis du fil (5 000 × 7 % = 350 relevé au minimum XOF), le même que l'annonce.
        stubThreadPrice(threadId, "5000", "XOF", "650");
        when(activeCurrencyResolver.resolve(travelerId)).thenReturn("XOF");
        when(walletService.getBalanceForUpdate(travelerId, "XOF")).thenReturn(new BigDecimal("10000"));

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("5000"), CommissionSource.WALLET_FIRST);

        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        ArgumentCaptor<BigDecimal> debited = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletService).debit(eq(travelerId), eq("XOF"), debited.capture(),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(threadId.toString()),
                eq("nego_commission_wallet_" + threadId));
        // Le montant du devis partagé est prélevé tel quel : 650 F CFA, sans centimes.
        assertThat(debited.getValue()).isEqualByComparingTo("650");
        assertThat(debited.getValue().scale()).isZero();
        verify(threadCommissionPricing).price(GRID_7_6_5.id(), new BigDecimal("5000"), "XOF",
                travelerId, senderId, threadId);
        verify(commissionRateResolver, never()).resolve(any(), any(), any(), any(), any());
    }

    @Test
    void settleNegotiationCommission_xofGrid_card_chargesWholeFrancs() throws StripeException {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = thread(threadId, "XOF", GRID_7_6_5.id());
        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        stubThreadPrice(threadId, "20000", "XOF", "1400");
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        traveler.setStripeCustomerId("cus_test");
        traveler.setCommissionPaymentMethodId("pm_test");
        when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        when(pi.getId()).thenReturn("pi_nego_xof");
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        when(stripeCashGateway.createPaymentIntent(params.capture(), any(RequestOptions.class))).thenReturn(pi);

        AcceptBidResponse response = service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("20000"), CommissionSource.CARD);

        assertThat(response.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        // Vecteur §4.6 : 20 000 F CFA → 1 400 F CFA ; XOF sans unité mineure.
        assertThat(params.getValue().getAmount()).isEqualTo(1400L);
        assertThat(params.getValue().getCurrency()).isEqualTo("xof");
    }

    @Test
    void settleNegotiationCommission_noGrid_legacyRateWithoutFloorUnchanged() {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = thread(threadId, "EUR", null);
        when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        when(commissionRateResolver.resolve(eq(travelerId), eq(senderId), isNull(), isNull(), eq(threadId)))
                .thenReturn(new BigDecimal("0.05"));
        when(walletService.getBalanceForUpdate(travelerId, "EUR")).thenReturn(new BigDecimal("10"));

        service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("5.00"), CommissionSource.WALLET_FIRST);

        // Calcul hérité : 5,00 € × 5 % = 0,25 €, sans plancher.
        verify(walletService).debit(eq(travelerId), eq("EUR"), argThat(a -> a.compareTo(new BigDecimal("0.25")) == 0),
                eq(WalletTransactionType.COMMISSION_DEDUCTED), anyString(), anyString());
        verifyNoInteractions(commissionGridService, threadCommissionPricing);
    }

    /**
     * Parties du colis déduites du colis et du trajet simulés par chaque test (lecture préalable
     * au verrou, cf. CashCommissionService#acceptCashBid).
     */
    private void stubBidParties() {
        lenient().when(bidRepo.findPartiesById(any())).thenAnswer(inv -> {
            java.util.Optional<com.yadony.api.matching.BidEntity> bid = bidRepo.findByIdForUpdate(inv.getArgument(0));
            if (bid == null || bid.isEmpty()) {
                return java.util.Optional.empty();
            }
            java.util.Optional<com.yadony.api.matching.AnnouncementEntity> announcement =
                    announcementRepo.findByIdForUpdate(bid.get().getAnnouncementId());
            java.util.UUID travelerId = announcement == null || announcement.isEmpty() ? null
                    : announcement.get().getTravelerId();
            java.util.UUID senderId = bid.get().getSenderId();
            return java.util.Optional.of(new BidRepository.BidParties() {
                @Override public java.util.UUID getSenderId() { return senderId; }
                @Override public java.util.UUID getTravelerId() { return travelerId; }
            });
        });
    }
}
