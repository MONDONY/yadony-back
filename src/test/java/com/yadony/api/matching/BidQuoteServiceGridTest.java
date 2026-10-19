package com.yadony.api.matching;

import com.yadony.api.auth.BlockService;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.dto.BidQuoteRequest;
import com.yadony.api.matching.dto.BidQuoteResponse;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.promo.PromoDiscount;
import com.yadony.api.promo.PromoService;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Devis et total expéditeur d'un bid de trajet quand une grille de commission est en vigueur
 * (spec §4.2, §5.4) : grille 7/6/5, minimums par défaut (1,00 €, 650 FCFA). Le montant affiché
 * avant validation doit être exactement celui que les rails prélèveront.
 */
@ExtendWith(MockitoExtension.class)
class BidQuoteServiceGridTest {

    private static final CommissionGrid GRID_7_6_5 = new CommissionGrid(UUID.randomUUID(),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                    new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                    new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
            CommissionGridService.defaultMinimums());

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RatingRepository ratingRepository;
    @Mock CancellationRepository cancellationRepository;
    @Mock BidGridItemRepository bidGridItemRepository;
    @Mock AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock BlockService blockService;
    @Mock BlockVisibility blockVisibility;
    @Mock CommissionRateResolver commissionRateResolver;
    @Mock PromoService promoService;
    @Mock StorageService storageService;
    @Mock BidPhotoService bidPhotoService;
    @Mock FirebaseContactService firebaseContact;
    @Mock PawapayProperties pawapayProperties;
    @Mock PaymentService paymentService;
    @Mock TripRescheduleRepository rescheduleRepository;
    @Mock BidRecipientLinkRepository recipientLinkRepository;
    @Mock CommissionGridService commissionGridService;

    BidService bidService;

    private static final String SENDER_UID = "uid-sender";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID THREAD_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bidService = new BidService(bidRepository, announcementRepository, userRepository, auditService,
                eventPublisher, ratingRepository, cancellationRepository, bidGridItemRepository,
                annGridItemRepository, blockService, blockVisibility, commissionRateResolver, promoService,
                storageService, bidPhotoService, firebaseContact, pawapayProperties, paymentService,
                TestMessages.resolver(), rescheduleRepository, recipientLinkRepository, commissionGridService);
        lenient().when(commissionGridService.require(GRID_7_6_5.id())).thenReturn(GRID_7_6_5);
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private UserEntity sender() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", SENDER_ID);
        u.setFirebaseUid(SENDER_UID);
        return u;
    }

    private AnnouncementEntity announcement(String pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANN_ID);
        a.setTravelerId(TRAVELER_ID);
        a.setPricePerKg(new BigDecimal(pricePerKg));
        a.setPricingMode(PricingMode.KG);
        a.setCurrency("EUR");
        return a;
    }

    /** Devis au poids : 1 kg au prix au kilo donné, grille 7/6/5 en vigueur. */
    private BidQuoteResponse quote(String pricePerKg, String promoCode) {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement(pricePerKg)));
        when(commissionGridService.current()).thenReturn(Optional.of(GRID_7_6_5));
        return bidService.quote(SENDER_UID, new BidQuoteRequest(ANN_ID, BigDecimal.ONE, promoCode, null));
    }

    private void reductionsWithoutCode(CommissionReductions r) {
        lenient().when(commissionRateResolver.reductions(TRAVELER_ID, SENDER_ID, null, null, null)).thenReturn(r);
    }

    // ── Devis ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("grille, net 20 € : commission 1,40 €, total 21,40 €, minimum non appliqué")
    void quote_grid_net20_totals_minimumFalse() {
        reductionsWithoutCode(CommissionReductions.NONE);

        BidQuoteResponse resp = quote("20.00", null);

        assertThat(resp.netEur()).isEqualByComparingTo("20.00");
        assertThat(resp.commissionEur()).isEqualByComparingTo("1.40");
        assertThat(resp.totalEur()).isEqualByComparingTo("21.40");
        assertThat(resp.rate()).isEqualByComparingTo("0.07");
        assertThat(resp.promoApplied()).isFalse();
        assertThat(resp.promoLabel()).isNull();
        assertThat(resp.minimumApplied()).isFalse();
        assertThat(resp.minimumAmount()).isEqualByComparingTo("1.00");
        assertThat(resp.commissionGridId()).isEqualTo(GRID_7_6_5.id());
        assertThat(resp.promoIneffectiveReason()).isNull();
        assertThat(resp.currency()).isEqualTo("EUR");
        // Le taux unique hérité n'est plus lu quand une grille est en vigueur.
        verify(commissionRateResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("grille, net 5 € : le minimum de 1,00 € s'applique, total 6,00 €")
    void quote_grid_net5_minimumApplied_amount1() {
        reductionsWithoutCode(CommissionReductions.NONE);

        BidQuoteResponse resp = quote("5.00", null);

        assertThat(resp.commissionEur()).isEqualByComparingTo("1.00");
        assertThat(resp.totalEur()).isEqualByComparingTo("6.00");
        assertThat(resp.minimumApplied()).isTrue();
        assertThat(resp.minimumAmount()).isEqualByComparingTo("1.00");
        // Taux réel (non borné, c'est un devis) : 1,00 / 5,00.
        assertThat(resp.rate()).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("code promo que le minimum rend sans effet : non appliqué, raison COMMISSION_MINIMUM")
    void quote_promoIneffective_reasonCommissionMinimum() {
        when(commissionRateResolver.reductions(TRAVELER_ID, SENDER_ID, "PROMO5", SENDER_ID, null))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        BidQuoteResponse resp = quote("5.00", "PROMO5");

        // 5 € × (7 % − 5 points) = 0,10 €, relevé au minimum : 1,00 € avec ou sans le code.
        assertThat(resp.commissionEur()).isEqualByComparingTo("1.00");
        assertThat(resp.totalEur()).isEqualByComparingTo("6.00");
        assertThat(resp.promoApplied()).isFalse();
        assertThat(resp.promoLabel()).isNull();
        assertThat(resp.promoIneffectiveReason()).isEqualTo("COMMISSION_MINIMUM");
        assertThat(resp.minimumApplied()).isTrue();
    }

    @Test
    @DisplayName("code promo sans effet hors minimum (commission déjà nulle) : aucune raison COMMISSION_MINIMUM")
    void quote_promoWithoutEffect_noMinimum_noReason() {
        // Taux spécial à 0 : rien à réduire, le minimum ne joue pas (commission nulle).
        when(commissionRateResolver.reductions(TRAVELER_ID, SENDER_ID, "PROMO5", SENDER_ID, null))
                .thenReturn(new CommissionReductions(BigDecimal.ZERO, PromoDiscount.points(new BigDecimal("0.05")), null));

        BidQuoteResponse resp = quote("20.00", "PROMO5");

        assertThat(resp.totalEur()).isEqualByComparingTo("20.00");
        assertThat(resp.promoApplied()).isFalse();
        assertThat(resp.minimumApplied()).isFalse();
        assertThat(resp.promoIneffectiveReason()).isNull();
    }

    @Test
    @DisplayName("code promo efficace : commission de base affichée, total réduit, libellé sur l'écart en montant")
    void quote_promoEffective_totalReduced_labelFromAmountGap() {
        // Code transmis tel que saisi (le service promo est insensible à la casse), libellé en majuscules.
        when(commissionRateResolver.reductions(TRAVELER_ID, SENDER_ID, "promo5", SENDER_ID, null))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        BidQuoteResponse resp = quote("100.00", "promo5");

        assertThat(resp.commissionEur()).isEqualByComparingTo("7.00");
        assertThat(resp.rate()).isEqualByComparingTo("0.07");
        // 100 € × (7 % − 5 points) = 2,00 €.
        assertThat(resp.totalEur()).isEqualByComparingTo("102.00");
        assertThat(resp.promoApplied()).isTrue();
        // Écart 7,00 − 2,00 = 5,00 € sur 100 € de net : 5 %.
        assertThat(resp.promoLabel()).contains("PROMO5").contains("5 % de réduction");
        assertThat(resp.promoIneffectiveReason()).isNull();
    }

    @Test
    @DisplayName("code promo invalide : 422 propagé, comme sans grille (pas de repli silencieux au devis)")
    void quote_grid_invalidPromo_propagates() {
        when(commissionRateResolver.reductions(TRAVELER_ID, SENDER_ID, "OLD", SENDER_ID, null))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code promo a expiré"));

        assertThatThrownBy(() -> quote("20.00", "OLD"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
    }

    @Test
    @DisplayName("bon de parrainage ×0,5 après le minimum : net 5 € → 0,50 €")
    void quote_grid_voucherAfterMinimum() {
        reductionsWithoutCode(new CommissionReductions(null, null, new BigDecimal("0.5")));

        BidQuoteResponse resp = quote("5.00", null);

        assertThat(resp.commissionEur()).isEqualByComparingTo("0.50");
        assertThat(resp.totalEur()).isEqualByComparingTo("5.50");
    }

    @Test
    @DisplayName("XOF, net 20 000 : 1 400 FCFA en francs entiers, minimum 650")
    void quote_grid_xof_wholeFrancs() {
        reductionsWithoutCode(CommissionReductions.NONE);
        AnnouncementEntity xof = announcement("20000");
        xof.setCurrency("XOF");
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(xof));
        when(commissionGridService.current()).thenReturn(Optional.of(GRID_7_6_5));

        BidQuoteResponse resp = bidService.quote(SENDER_UID, new BidQuoteRequest(ANN_ID, BigDecimal.ONE, null, null));

        assertThat(resp.commissionEur()).isEqualByComparingTo("1400");
        assertThat(resp.totalEur()).isEqualByComparingTo("21400");
        assertThat(resp.minimumAmount()).isEqualByComparingTo("650");
        assertThat(resp.currency()).isEqualTo("XOF");
    }

    @Test
    @DisplayName("sans grille : les nouveaux champs restent neutres")
    void quote_noGrid_newFieldsNeutral() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement("20.00")));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        BidQuoteResponse resp = bidService.quote(SENDER_UID, new BidQuoteRequest(ANN_ID, BigDecimal.ONE, null, null));

        assertThat(resp.totalEur()).isEqualByComparingTo("22.40");
        assertThat(resp.minimumApplied()).isFalse();
        assertThat(resp.minimumAmount()).isNull();
        assertThat(resp.commissionGridId()).isNull();
        assertThat(resp.promoIneffectiveReason()).isNull();
        verify(commissionRateResolver, never()).reductions(any(), any(), any(), any(), any());
    }

    // ── Total expéditeur d'un bid à grille ─────────────────────────────────

    private BidEntity gridBid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", BID_ID);
        b.setAnnouncementId(ANN_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(BidStatus.PENDING);
        b.setWeightKg(new BigDecimal("5"));
        b.setCurrency("EUR");
        b.setCommissionGridId(GRID_7_6_5.id());
        return b;
    }

    @Test
    @DisplayName("bid à grille : total expéditeur = brut du calculateur, réductions du premier paiement carte")
    void toResponse_gridBid_totalSenderAmountFromCalculator() {
        BidEntity bid = gridBid();
        bid.setPromoCode("PROMO5");
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement("4.00")));
        // Bon ×0,5 rattaché au bid : 5 kg × 4 € = 20 € → 1,40 € × 0,5 = 0,70 €.
        when(commissionRateResolver.displayReductionsForBid(TRAVELER_ID, SENDER_ID, "PROMO5", null, BID_ID))
                .thenReturn(new CommissionReductions(null, null, new BigDecimal("0.5")));

        BidResponse resp = bidService.toResponse(bid, sender(), SENDER_ID);

        assertThat(resp.totalSenderAmountEur()).isEqualByComparingTo("20.70");
        assertThat(resp.pricePerKgSenderEur()).isEqualByComparingTo("4.14");
        // Le net du voyageur ne descend jamais vers l'expéditeur.
        assertThat(resp.totalNetAmountEur()).isNull();
        verify(commissionRateResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("bid à grille au code déjà racheté : remise du rachat, jamais revalidée (comme la reprise carte)")
    void toResponse_gridBid_redeemedPromo_usesRedeemedDiscount() {
        BidEntity bid = gridBid();
        UUID promoCodeId = UUID.randomUUID();
        bid.setPromoCode("PROMO5");
        bid.setPromoCodeId(promoCodeId);
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement("20.00")));
        // Règle du code racheté : celle du résolveur (CommissionRateResolverTest#reductionsForBid_*).
        when(commissionRateResolver.displayReductionsForBid(TRAVELER_ID, SENDER_ID, "PROMO5", promoCodeId, BID_ID))
                .thenReturn(new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null));

        BidResponse resp = bidService.toResponse(bid, sender(), SENDER_ID);

        // 5 kg × 20 € = 100 € : 100 × (7 % − 5 points) = 2,00 €. Revalidé, un code à usage
        // unique échouerait sur son propre rachat et le total affiché remonterait à 107,00 €.
        assertThat(resp.totalSenderAmountEur()).isEqualByComparingTo("102.00");
        verify(commissionRateResolver, never()).reductions(any(), any(), any(), any(), any());
    }

    /** Événement d'un fil à grille scellé : 5 kg, net 100 €, payé {@code senderGross} (nul en espèces). */
    private PackageRequestAcceptedEvent threadAccepted(PaymentMethod method, BigDecimal senderGross) {
        return new PackageRequestAcceptedEvent(THREAD_ID, UUID.randomUUID(), SENDER_ID, TRAVELER_ID,
                new BigDecimal("100.00"), ANN_ID, new BigDecimal("5"), "Vêtements", "CLOTHING",
                method == PaymentMethod.STRIPE ? "pi_thread" : null, "Fatou Diop", "+221771234567",
                LocalDateTime.now(), "1.2.3.4", method, List.of(),
                method == PaymentMethod.CASH ? "WALLET" : null, null, null, "EUR",
                GRID_7_6_5.id(), senderGross);
    }

    /** Bid que le vrai {@link ThreadAcceptedBidListener} matérialise pour cet événement. */
    private BidEntity materialize(PackageRequestAcceptedEvent event) {
        ThreadAcceptedBidListener listener = new ThreadAcceptedBidListener(bidRepository,
                announcementRepository, auditService, eventPublisher, storageService, bidPhotoService,
                promoService, mock(CommissionVoucherService.class));
        when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID)).thenReturn(Optional.empty());
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", BID_ID);
            return b;
        });
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement("4.00")));
        listener.onPackageRequestAccepted(event);
        ArgumentCaptor<BidEntity> saved = ArgumentCaptor.forClass(BidEntity.class);
        verify(bidRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("bid matérialisé d'un fil à grille payé carte : « Mes envois » affiche le total payé (bon ×0,5)")
    void toResponse_bidMaterializedFromGridThread_showsSealedSenderGross() {
        // Net 100 € : grille 7 % → 7,00 €, bon ×0,5 → 3,50 € ; l'expéditeur a payé 103,50 €.
        BidEntity bid = materialize(threadAccepted(PaymentMethod.STRIPE, new BigDecimal("103.50")));

        BidResponse resp = bidService.toResponse(bid, sender(), SENDER_ID);

        // Recalculé depuis le net sans les réductions du fil, le total serait 107,00 €.
        assertThat(resp.totalSenderAmountEur()).isEqualByComparingTo("103.50");
        verify(commissionGridService, never()).require(any());
        verify(commissionRateResolver, never()).displayReductionsForBid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bid matérialisé d'un fil à grille réglé en espèces : net + commission publique de la grille")
    void toResponse_bidMaterializedFromCashGridThread_showsPublicGross() {
        // Espèces : senderGross nul, l'expéditeur remet le net, la commission est prélevée au
        // voyageur. Comme le chemin hérité (net × (1 + taux)), le total affiché est le net plus la
        // commission de la grille, sans réduction personnelle : 100 € + 7,00 €.
        BidEntity bid = materialize(threadAccepted(PaymentMethod.CASH, null));

        BidResponse resp = bidService.toResponse(bid, sender(), SENDER_ID);

        assertThat(bid.getNegotiatedGrossEur()).isNull();
        assertThat(resp.totalSenderAmountEur()).isEqualByComparingTo("107.00");
        verify(commissionRateResolver, never()).displayReductionsForBid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bid à grille au brut négocié : le brut convenu, tel quel")
    void toResponse_gridBid_negotiatedGross_isKept() {
        BidEntity bid = gridBid();
        bid.setNegotiatedNetEur(new BigDecimal("50.00"));
        bid.setNegotiatedGrossEur(new BigDecimal("51.00"));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement("4.00")));

        BidResponse resp = bidService.toResponse(bid, sender(), SENDER_ID);

        assertThat(resp.totalSenderAmountEur()).isEqualByComparingTo("51.00");
        verify(commissionGridService, never()).require(any());
    }
}
