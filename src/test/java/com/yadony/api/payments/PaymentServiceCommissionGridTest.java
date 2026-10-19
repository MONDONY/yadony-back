package com.yadony.api.payments;

import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.PaymentResponse;
import com.yadony.api.promo.PromoDiscount;
import com.yadony.api.promo.PromoRedemptionEntity;
import com.yadony.api.promo.PromoService;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rail carte (spec commission par paliers §4.2, §4.4) : un bid ou un fil qui porte une grille
 * figée est facturé par {@code CommissionCalculator} sur CETTE grille, jamais sur la grille du
 * jour ; sans grille, le calcul hérité au taux unique ne change pas d'un centime.
 *
 * <p>Grille de référence 7/6/5 (bornes 0, 100 €, 300 €), minimum 1 € (0,95 CHF).
 */
@DisplayName("PaymentService — commission carte par grille figée")
class PaymentServiceCommissionGridTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BidRepository bidRepository = mock(BidRepository.class);
    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final CommissionRateResolver resolver = mock(CommissionRateResolver.class);
    private final PromoService promoService = mock(PromoService.class);
    private final CommissionVoucherService voucherService = mock(CommissionVoucherService.class);
    private final CommissionGridService gridService = mock(CommissionGridService.class);

    private PaymentService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID annId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();
    private final UUID gridId = UUID.randomUUID();

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 11, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository, mock(BidGridItemRepository.class), announcementRepository,
                paymentRepository, mock(AuditService.class), mock(ApplicationEventPublisher.class),
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.yadony.api.common.stripe.AdminAlertService.class),
                resolver, promoService, new StripeGatewayImpl(null),
                PaymentServiceTestFactory.stubbedContacts(), voucherService,
                mock(ConnectAccountProvisioner.class));
        ReflectionTestUtils.setField(service, "commissionGridService", gridService);
        lenient().when(gridService.require(gridId)).thenReturn(GRID_7_6_5);
        // Règle de repli du code promo : celle du résolveur (CommissionRateResolverTest), exécutée
        // pour de vrai sur le mock ; les bouchons de reductions(...) restent la source des réductions.
        lenient().when(resolver.reductionsWithPromoFallback(any(), any(), any(), any())).thenCallRealMethod();
        lenient().when(resolver.reductionsForBid(any(), any(), any(), isNull(), any())).thenCallRealMethod();
        lenient().when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            if (p.getId() == null) {
                PaymentServiceTestFactory.setId(p, UUID.randomUUID());
            }
            return p;
        });

        UserEntity sender = new UserEntity();
        PaymentServiceTestFactory.setId(sender, senderId);
        sender.setFirebaseUid("uid-sender");
        sender.setStripeCustomerId("cus_sender");
        lenient().when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        UserEntity traveler = new UserEntity();
        PaymentServiceTestFactory.setId(traveler, travelerId);
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        AnnouncementEntity ann = new AnnouncementEntity();
        PaymentServiceTestFactory.setId(ann, annId);
        ann.setTravelerId(travelerId);
        ann.setPricePerKg(new BigDecimal("5.00"));
        lenient().when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        lenient().when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
    }

    // ── Fabriques ───────────────────────────────────────────────────────────────

    /** Bid direct au kilo : {@code kg × 5 €/kg} de net. */
    private BidEntity bid(String kg, UUID frozenGrid) {
        BidEntity b = new BidEntity();
        PaymentServiceTestFactory.setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(new BigDecimal(kg));
        b.setStatus(BidStatus.AWAITING_PAYMENT);
        b.setCommissionGridId(frozenGrid);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(b));
        lenient().when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        return b;
    }

    private CreatePaymentRequest request() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setBidId(bidId);
        return req;
    }

    private interface Call<T> {
        T run();
    }

    /** Lance l'appel avec Stripe simulé ; rend les paramètres du PaymentIntent créé. */
    private PaymentIntentCreateParams withStripe(Call<?> call) {
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            ArgumentCaptor<PaymentIntentCreateParams> captor = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getId()).thenReturn("pi_grid");
            when(pi.getClientSecret()).thenReturn("pi_grid_secret");
            piStatic.when(() -> PaymentIntent.create(captor.capture())).thenReturn(pi);
            call.run();
            return captor.getValue();
        }
    }

    private PaymentEntity savedPayment() {
        ArgumentCaptor<PaymentEntity> saved = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository).save(saved.capture());
        return saved.getValue();
    }

    private void reductions(String promoCode, UUID reference, CommissionReductions r) {
        if (promoCode == null) {
            when(resolver.reductions(travelerId, senderId, null, null, reference)).thenReturn(r);
        } else {
            when(resolver.reductions(travelerId, senderId, promoCode, senderId, reference)).thenReturn(r);
        }
    }

    // ── createEscrow : bid direct ───────────────────────────────────────────────

    @Test
    @DisplayName("net 10 € sur la grille 7/6/5 : 0,70 € relevé au minimum, commission 1,00 €, brut 11,00 €")
    void createEscrow_grid_net10_commission1_gross11() {
        BidEntity bid = bid("2", gridId);
        reductions(null, bidId, CommissionReductions.NONE);

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(params.getAmount()).isEqualTo(1100L);
        assertThat(params.getMetadata()).containsEntry("commission_minor", "100")
                .containsEntry("commission_amount", "1.00")
                .containsEntry("commission_rate", "0.100000");
        PaymentEntity payment = savedPayment();
        assertThat(payment.getAmount()).isEqualByComparingTo("11.00");
        assertThat(payment.getCommissionAmount()).isEqualByComparingTo("1.00");
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.1");
        verify(resolver, never()).resolve(any(), any());
        verify(voucherService).consume(senderId, bidId);
    }

    @Test
    @DisplayName("la grille figée sur le bid est relue par son identifiant, jamais la grille du jour")
    void createEscrow_usesFrozenGridNotCurrent() {
        UUID frozen = UUID.randomUUID();
        CommissionGrid tenPercent = new CommissionGrid(frozen, LocalDateTime.of(2026, 10, 1, 0, 0),
                List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.10"))),
                CommissionGridService.defaultMinimums());
        when(gridService.require(frozen)).thenReturn(tenPercent);
        lenient().when(gridService.current()).thenReturn(Optional.of(GRID_7_6_5));
        bid("4", frozen);
        reductions(null, bidId, CommissionReductions.NONE);

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        // 20,00 € à 10 % = 2,00 € ; la grille du jour (7 %) aurait donné 1,40 €.
        assertThat(params.getAmount()).isEqualTo(2200L);
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("2.00");
        verify(gridService).require(frozen);
        verify(gridService, never()).current();
    }

    @Test
    @DisplayName("code promo annulé par le minimum : commission 1,00 €, code non consommé")
    void createEscrow_promoIneffective_notRedeemed() {
        BidEntity bid = bid("2", gridId);
        bid.setPromoCode("MOINS5");
        reductions("MOINS5", bidId, new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        PaymentResponse[] response = new PaymentResponse[1];
        withStripe(() -> response[0] = service.createEscrow(request(), "uid-sender"));

        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("1.00");
        assertThat(response[0].isPromoApplied()).isFalse();
        verify(promoService, never()).redeem(anyString(), any(), any(), any());
        assertThat(bid.getPromoCodeId()).isNull();
    }

    @Test
    @DisplayName("code promo qui baisse la commission : consommé au taux effectif, promoApplied vrai")
    void createEscrow_promoEffective_redeemedAtEffectiveRate() {
        BidEntity bid = bid("20", gridId);
        bid.setPromoCode("MOINS2");
        reductions("MOINS2", bidId, new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.02")), null));
        PromoRedemptionEntity redemption = mock(PromoRedemptionEntity.class);
        UUID promoCodeId = UUID.randomUUID();
        when(redemption.getPromoCodeId()).thenReturn(promoCodeId);
        when(promoService.redeem(eq("MOINS2"), eq(senderId), eq(bidId), any())).thenReturn(redemption);

        PaymentResponse[] response = new PaymentResponse[1];
        withStripe(() -> response[0] = service.createEscrow(request(), "uid-sender"));

        // 100,00 € : 7,00 € de grille, taux effectif 7 % − 2 points = 5 %, soit 5,00 €.
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("5.00");
        assertThat(response[0].isPromoApplied()).isTrue();
        ArgumentCaptor<BigDecimal> rate = ArgumentCaptor.forClass(BigDecimal.class);
        verify(promoService).redeem(eq("MOINS2"), eq(senderId), eq(bidId), rate.capture());
        assertThat(rate.getValue()).isEqualByComparingTo("0.05");
        assertThat(bid.getPromoCodeId()).isEqualTo(promoCodeId);
    }

    @Test
    @DisplayName("code promo devenu invalide : repli sans code, sans rachat, comme le chemin hérité")
    void createEscrow_promoInvalid_fallsBackWithoutPromo() {
        BidEntity bid = bid("20", gridId);
        bid.setPromoCode("EXPIRE");
        when(resolver.reductions(travelerId, senderId, "EXPIRE", senderId, bidId))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code promo a expiré"));
        reductions(null, bidId, CommissionReductions.NONE);

        withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("7.00");
        verify(promoService, never()).redeem(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("bon ×0,5 appliqué après le minimum : net 5 €, commission 0,50 €, bon consommé")
    void createEscrow_voucher_afterMinimum_net5_commission050() {
        BidEntity bid = bid("1", gridId);
        reductions(null, bidId, new CommissionReductions(null, null, new BigDecimal("0.5")));

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(params.getAmount()).isEqualTo(550L);
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("0.50");
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.1");
        verify(voucherService).consume(senderId, bidId);
    }

    @Test
    @DisplayName("taux spécial 4 % sur 100 € : 4,00 € au lieu des 7,00 € de la grille")
    void createEscrow_override_appliedOnGrid() {
        bid("20", gridId);
        reductions(null, bidId, new CommissionReductions(new BigDecimal("0.04"), null, null));

        withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("sans grille : taux unique hérité, net 10 € à 5 % = 0,50 €, la grille n'est jamais lue")
    void createEscrow_noGrid_legacyUnchanged() {
        BidEntity bid = bid("2", null);
        when(resolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(params.getAmount()).isEqualTo(1050L);
        assertThat(params.getMetadata()).containsEntry("commission_rate", "0.05");
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("0.50");
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.05");
        verifyNoInteractions(gridService);
        verify(resolver, never()).reductions(any(), any(), any(), any(), any());
    }

    /** Paiement déjà enregistré (essai précédent échoué) avec ses montants arrondis. */
    private PaymentEntity failedPayment(String amount, String commission) {
        PaymentEntity p = new PaymentEntity();
        PaymentServiceTestFactory.setId(p, UUID.randomUUID());
        p.setStatus(PaymentStatus.FAILED);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setCurrency("EUR");
        return p;
    }

    @Test
    @DisplayName("tentative carte vue de l'affichage : rien sans paiement, montants rejoués, ou re-tarif si le net a changé")
    void gridCardAttempt_mirrorsTheRetryOfCreateEscrow() {
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertThat(service.gridCardAttempt(bidId, new BigDecimal("0.50"), SupportedCurrency.EUR)).isEmpty();

        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(failedPayment("1.50", "1.00")));
        assertThat(service.gridCardAttempt(bidId, new BigDecimal("0.50"), SupportedCurrency.EUR))
                .hasValueSatisfying(a -> assertThat(a.committed().gross()).isEqualByComparingTo("1.50"));
        // Net passé à 10 € : la reprise re-tarifera (createEscrow_grid_retryWithChangedNet_withoutRedeemedPromo).
        assertThat(service.gridCardAttempt(bidId, new BigDecimal("10.00"), SupportedCurrency.EUR))
                .hasValueSatisfying(a -> assertThat(a.committed()).isNull());
        verifyNoInteractions(gridService, promoService);
    }

    @Test
    @DisplayName("net 0,50 € sous le minimum : commission 1,00, brut 1,50, taux enregistré borné à 0,999999")
    void createEscrow_grid_netBelowMinimum_storesCappedRate() {
        BidEntity bid = bid("0.1", gridId);
        reductions(null, bidId, CommissionReductions.NONE);

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(params.getAmount()).isEqualTo(150L);
        assertThat(params.getMetadata()).containsEntry("commission_rate", "0.999999");
        PaymentEntity payment = savedPayment();
        assertThat(payment.getAmount()).isEqualByComparingTo("1.50");
        assertThat(payment.getCommissionAmount()).isEqualByComparingTo("1.00");
        // Le taux réel (200 %) violerait bids_commission_rate_range [0, 1[ (V118).
        assertThat(bid.getCommissionRate()).isEqualTo(new BigDecimal("0.999999"));
    }

    @Test
    @DisplayName("nouvelle tentative sur un bid à grille : montants du paiement existant réutilisés, rien relu ni reconsommé")
    void createEscrow_grid_retryReusesExistingPaymentAmounts() {
        BidEntity bid = bid("0.1", gridId);
        bid.setPromoCode("MOINS5");
        bid.setCommissionRate(new BigDecimal("0.999999"));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(failedPayment("1.50", "1.00")));

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        // 1,50 € comme au premier essai ; rejouer le taux borné aurait donné 0,50 × 0,999999 = 0,50.
        assertThat(params.getAmount()).isEqualTo(150L);
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("1.00");
        assertThat(bid.getCommissionRate()).isEqualTo(new BigDecimal("0.999999"));
        verify(resolver, never()).reductions(any(), any(), any(), any(), any());
        verifyNoInteractions(gridService);
        verify(promoService, never()).redeem(anyString(), any(), any(), any());
        verify(voucherService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("net changé depuis l'essai précédent, code déjà racheté : nouveau devis avec sa remise, jamais revalidée")
    void createEscrow_grid_retryWithChangedNet_keepsRedeemedPromoWithoutRevalidation() {
        // Premier essai à 100 € (7,00 − 2 points = 5,00) ; le voyageur a passé son prix de
        // 5 à 6 €/kg : 120 € de net, la remise du code racheté doit suivre.
        BidEntity bid = bid("20", gridId);
        bid.setPromoCode("UNIQUE2");
        UUID promoCodeId = UUID.randomUUID();
        bid.setPromoCodeId(promoCodeId);
        bid.setCommissionRate(new BigDecimal("0.050000"));
        announcementRepository.findById(annId).orElseThrow().setPricePerKg(new BigDecimal("6.00"));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(failedPayment("105.00", "5.00")));
        // Règle du code racheté : celle du résolveur (CommissionRateResolverTest#reductionsForBid_*).
        // doReturn : when(...) exécuterait la vraie méthode bouchonnée au setUp sur des arguments nuls.
        org.mockito.Mockito.doReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.02")),
                        new BigDecimal("0.5")))
                .when(resolver).reductionsForBid(any(), any(), any(), eq(promoCodeId), eq(bidId));

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        // 120 € : grille 7,00 + 1,20 = 8,20 (6,8333 %) − 2 points = 4,8333 % → 5,80 ; bon ×0,5 → 2,90.
        assertThat(params.getAmount()).isEqualTo(12290L);
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("2.90");
        assertThat(bid.getCommissionRate()).isEqualTo(new BigDecimal("0.024167"));
        verify(resolver, never()).reductions(any(), any(), anyString(), any(), any());
        verify(promoService, never()).validateAndGetDiscount(any(), any(), any());
        verify(promoService, never()).redeem(anyString(), any(), any(), any());
        verify(voucherService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("net changé depuis l'essai précédent, sans code racheté : nouveau devis sans promo, taux du bid mis à jour")
    void createEscrow_grid_retryWithChangedNet_withoutRedeemedPromo() {
        BidEntity bid = bid("2", gridId);
        bid.setPromoCode("JAMAISAPPLIQUE");
        bid.setCommissionRate(new BigDecimal("0.999999"));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(failedPayment("6.00", "1.00")));
        reductions(null, bidId, CommissionReductions.NONE);

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(params.getAmount()).isEqualTo(1100L);
        assertThat(bid.getCommissionRate()).isEqualTo(new BigDecimal("0.100000"));
        verify(gridService).require(gridId);
        verify(resolver, never()).reductions(any(), any(), anyString(), any(), any());
        verifyNoInteractions(promoService);
        verify(voucherService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("bid négocié à grille : brut et net de l'accord gardés tels quels, commission = brut − net")
    void createEscrow_negotiatedGridBid_keepsAgreedSpread() {
        BidEntity bid = bid("5", gridId);
        bid.setNegotiatedGrossEur(new BigDecimal("45.00"));
        bid.setNegotiatedNetEur(new BigDecimal("43.00"));
        bid.setCommissionRate(new BigDecimal("0.046512"));

        PaymentIntentCreateParams params = withStripe(() -> service.createEscrow(request(), "uid-sender"));

        assertThat(params.getAmount()).isEqualTo(4500L);
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("2.00");
        verifyNoInteractions(gridService);
        verify(resolver, never()).reductions(any(), any(), any(), any(), any());
    }

    // ── createNegotiationEscrow : fil d'une demande de colis ────────────────────

    @Test
    @DisplayName("fil à grille, net 10 € : commission 1,00 €, brut 11,00 €, taux effectif 0,1 exposé")
    void createNegotiationEscrow_grid_eur_minimum() {
        reductions(null, threadId, CommissionReductions.NONE);

        PaymentResponse[] response = new PaymentResponse[1];
        PaymentIntentCreateParams params = withStripe(() -> response[0] = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("10.00"), null, null, "EUR", gridId));

        assertThat(params.getAmount()).isEqualTo(1100L);
        assertThat(params.getMetadata()).containsEntry("commission_rate", "0.100000");
        assertThat(response[0].getAmount()).isEqualByComparingTo("11.00");
        assertThat(response[0].getCommissionAmount()).isEqualByComparingTo("1.00");
        assertThat(response[0].getCommissionRate()).isEqualByComparingTo("0.1");
        assertThat(response[0].isPromoApplied()).isFalse();
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("fil à grille en CHF : arrondi unique à l'unité mineure (20,37 × 7 % = 1,4259 → 1,43)")
    void createNegotiationEscrow_grid_chf_roundedToMinorUnit() {
        reductions(null, threadId, CommissionReductions.NONE);

        PaymentIntentCreateParams params = withStripe(() -> service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("20.37"), null, null, "CHF", gridId));

        assertThat(params.getAmount()).isEqualTo(2180L);
        assertThat(params.getCurrency()).isEqualTo("chf");
        assertThat(savedPayment().getCommissionAmount()).isEqualByComparingTo("1.43");
    }

    @Test
    @DisplayName("fil à grille en XOF : la carte reste refusée avant tout calcul de commission")
    void createNegotiationEscrow_grid_xof_rejectedBeforePricing() {
        Throwable thrown = catchThrowable(() -> service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("20000"), null, null, "XOF", gridId));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) thrown).getErrorCode())
                .isEqualTo("payment-method-unavailable-for-currency");
        verifyNoInteractions(gridService);
    }

    @Test
    @DisplayName("fil à grille, promo efficace : promoApplied vrai et taux effectif après promo")
    void createNegotiationEscrow_grid_promoEffective() {
        reductions("MOINS2", threadId,
                new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.02")), null));

        PaymentResponse[] response = new PaymentResponse[1];
        withStripe(() -> response[0] = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("100.00"), "MOINS2", null, "EUR", gridId));

        assertThat(response[0].getCommissionAmount()).isEqualByComparingTo("5.00");
        assertThat(response[0].getCommissionRate()).isEqualByComparingTo("0.05");
        assertThat(response[0].isPromoApplied()).isTrue();
    }

    @Test
    @DisplayName("fil à grille, promo absorbée par le minimum : promoApplied faux (le contrôleur efface le code)")
    void createNegotiationEscrow_grid_promoIneffective() {
        reductions("MOINS5", threadId,
                new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        PaymentResponse[] response = new PaymentResponse[1];
        withStripe(() -> response[0] = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("10.00"), "MOINS5", null, "EUR", gridId));

        assertThat(response[0].getCommissionAmount()).isEqualByComparingTo("1.00");
        assertThat(response[0].isPromoApplied()).isFalse();
    }

    @Test
    @DisplayName("fil à grille, promo invalide : repli sans code")
    void createNegotiationEscrow_grid_promoInvalid_fallsBack() {
        when(resolver.reductions(travelerId, senderId, "EXPIRE", senderId, threadId))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code promo a expiré"));
        reductions(null, threadId, CommissionReductions.NONE);

        PaymentResponse[] response = new PaymentResponse[1];
        withStripe(() -> response[0] = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("100.00"), "EXPIRE", null, "EUR", gridId));

        assertThat(response[0].getCommissionAmount()).isEqualByComparingTo("7.00");
        assertThat(response[0].isPromoApplied()).isFalse();
    }

    @Test
    @DisplayName("nouvelle tentative sur un fil à grille : montants du paiement existant réutilisés, taux réel exposé")
    void createNegotiationEscrow_grid_retryReusesExistingPaymentAmounts() {
        when(paymentRepository.findByNegotiationThreadId(threadId))
                .thenReturn(Optional.of(failedPayment("1.50", "1.00")));

        PaymentResponse[] response = new PaymentResponse[1];
        PaymentIntentCreateParams params = withStripe(() -> response[0] = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("0.50"), "MOINS2",
                new BigDecimal("0.999999"), "EUR", gridId));

        assertThat(params.getAmount()).isEqualTo(150L);
        assertThat(response[0].getCommissionAmount()).isEqualByComparingTo("1.00");
        assertThat(response[0].getCommissionRate()).isEqualByComparingTo("2");
        assertThat(response[0].isPromoApplied()).isTrue();
        verifyNoInteractions(gridService);
        verify(resolver, never()).reductions(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("fil à grille avec un taux persisté mais sans paiement enregistré : devis sur la grille figée")
    void createNegotiationEscrow_grid_persistedRateWithoutPayment_repricesOnGrid() {
        reductions(null, threadId, CommissionReductions.NONE);

        PaymentResponse[] response = new PaymentResponse[1];
        withStripe(() -> response[0] = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("0.50"), null,
                new BigDecimal("0.999999"), "EUR", gridId));

        // net × 0,999999 aurait donné 0,50 ; le devis redonne le minimum de 1,00.
        assertThat(response[0].getCommissionAmount()).isEqualByComparingTo("1.00");
        verify(gridService).require(gridId);
    }

    @Test
    @DisplayName("fil à grille, net nul : 422 invalid-amount, le calculateur n'est jamais appelé")
    void createNegotiationEscrow_grid_nonPositiveNet_rejected() {
        Throwable thrown = catchThrowable(() -> service.createNegotiationEscrow(
                threadId, senderId, travelerId, BigDecimal.ZERO, null, null, "EUR", gridId));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("invalid-amount");
        verifyNoInteractions(gridService);
    }

    @Test
    @DisplayName("fil sans grille : surcharge historique, taux unique hérité inchangé")
    void createNegotiationEscrow_noGrid_legacyUnchanged() {
        when(resolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.05"));

        PaymentResponse[] response = new PaymentResponse[1];
        withStripe(() -> response[0] = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("10.00"), null, null, "EUR"));

        assertThat(response[0].getCommissionAmount()).isEqualByComparingTo("0.50");
        assertThat(response[0].getCommissionRate()).isEqualByComparingTo("0.05");
        verifyNoInteractions(gridService);
        verify(resolver, never()).reductions(any(), any(), any(), any(), any());
    }
}
