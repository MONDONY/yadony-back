package com.yadony.api.payments.cash;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.promo.PromoService;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.voucher.CommissionVoucherEntity;
import com.yadony.api.voucher.CommissionVoucherRepository;
import com.yadony.api.voucher.CommissionVoucherService;
import com.yadony.api.voucher.VoucherConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Non-regression : le bon de parrainage de l'EXPÉDITEUR rabattait la commission de
 * chaque envoi en espèces sans jamais être consommé ({@code peekActive} est une lecture
 * pure, et aucun chemin espèces n'appelait {@code consume}). Un parrain gardait donc sa
 * remise sur un nombre illimité d'envois en espèces, puis consommait le bon une dernière
 * fois en carte.
 *
 * <p>On câble ici les VRAIS {@link CommissionVoucherService} et
 * {@link CommissionRateResolver} au-dessus d'un dépôt simulé, pour observer le taux
 * réellement appliqué à deux envois successifs.
 */
@ExtendWith(MockitoExtension.class)
class CashSenderVoucherConsumptionTest {

    @Mock private UserRepository userRepo;
    @Mock private BidRepository bidRepo;
    @Mock private AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher events;
    @Mock private WalletService walletService;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private AuditService auditService;
    @Mock private NegotiationThreadRepository negotiationThreadRepository;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private PlatformSettingsService settings;
    @Mock private PromoService promoService;
    @Mock private CommissionVoucherRepository voucherRepository;

    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    private CommissionVoucherEntity voucher;
    private CashCommissionService service;

    @BeforeEach
    void setUp() {
        voucher = new CommissionVoucherEntity();
        ReflectionTestUtils.setField(voucher, "id", UUID.randomUUID());
        voucher.setUserId(SENDER_ID);
        voucher.setFactor(new BigDecimal("0.50"));
        voucher.setGrantedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        voucher.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMonths(6));
        voucher.setSourceInvitationId(UUID.randomUUID());

        // Dépôt simulé, fidèle aux requêtes réelles : un bon consommé sort du « actif ».
        lenient().when(voucherRepository.findActiveByUserId(any(), any())).thenAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            return (SENDER_ID.equals(userId) && voucher.getConsumedAt() == null)
                    ? List.of(voucher) : List.of();
        });
        lenient().when(voucherRepository.findByConsumedOnBidIdAndUserId(any(), any()))
                .thenAnswer(inv -> {
                    UUID ref = inv.getArgument(0);
                    UUID userId = inv.getArgument(1);
                    return (ref.equals(voucher.getConsumedOnBidId()) && userId.equals(voucher.getUserId()))
                            ? Optional.of(voucher) : Optional.empty();
                });
        lenient().when(voucherRepository.findByIdForUpdate(voucher.getId()))
                .thenReturn(Optional.of(voucher));
        lenient().when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CommissionVoucherService voucherService = new CommissionVoucherService(
                voucherRepository, auditService, new VoucherConfig());

        lenient().when(settings.commissionRate()).thenReturn(new BigDecimal("0.10"));
        lenient().when(userRepo.findById(any())).thenReturn(Optional.empty());
        CommissionRateResolver resolver =
                new CommissionRateResolver(userRepo, settings, promoService, voucherService);

        service = new CashCommissionService(
                new CommissionProperties(new BigDecimal("0.10"), BigDecimal.ZERO, 24),
                userRepo, bidRepo, announcementRepo, events, walletService,
                walletTransactionRepository, auditService, resolver, negotiationThreadRepository,
                new StripeCashGatewayImpl(), bidGridItemRepository,
                org.mockito.Mockito.mock(com.yadony.api.auth.FirebaseContactService.class),
                voucherService);

        lenient().when(bidGridItemRepository.findByBidId(any())).thenReturn(List.of());
        lenient().when(walletTransactionRepository.existsByUserIdAndBidIdAndType(any(), any(), any()))
                .thenReturn(false);
        lenient().when(bidRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", UUID.randomUUID());
        ann.setTravelerId(TRAVELER_ID);
        ann.setPricePerKg(new BigDecimal("10"));
        return ann;
    }

    private BidEntity bid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setSenderId(SENDER_ID);
        b.setWeightKg(new BigDecimal("10"));
        b.setCurrency("EUR");
        return b;
    }

    @Test
    @DisplayName("Deux envois en especes successifs : seul le premier beneficie du bon")
    void voucherDiscountsOnlyTheFirstCashShipment() {
        AnnouncementEntity ann = announcement();

        // Envoi 1 : 100 EUR de net, taux global 10 % rabattu de moitie par le bon.
        BidEntity first = bid();
        BigDecimal firstCommission = service.computeBidCommission(first, ann);
        assertThat(firstCommission).isEqualByComparingTo("5.00");
        assertThat(first.getCommissionRate()).isEqualByComparingTo("0.05");

        // Le prelevement effectif consomme le bon.
        service.chargeCommissionFromWallet(first, TRAVELER_ID, firstCommission);
        assertThat(voucher.getConsumedAt()).isNotNull();
        assertThat(voucher.getConsumedOnBidId()).isEqualTo(first.getId());

        // Envoi 2 : plus de bon disponible, plein tarif.
        BidEntity second = bid();
        BigDecimal secondCommission = service.computeBidCommission(second, ann);
        assertThat(secondCommission).isEqualByComparingTo("10.00");
        assertThat(second.getCommissionRate()).isEqualByComparingTo("0.10");
    }

    @Test
    @DisplayName("Rejouer le meme envoi ne fait pas remonter le taux au plein tarif")
    void replayingTheSameCashShipmentKeepsTheDiscountedRate() {
        AnnouncementEntity ann = announcement();
        BidEntity bid = bid();

        BigDecimal commission = service.computeBidCommission(bid, ann);
        service.chargeCommissionFromWallet(bid, TRAVELER_ID, commission);

        // Reessai sur le MEME bid (carte refusee puis retentee, 3DS complete plus tard) :
        // le bon deja consomme pour cette reference reste celui qui fait foi.
        BigDecimal replayed = service.computeBidCommission(bid, ann);

        assertThat(replayed).isEqualByComparingTo("5.00");
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.05");
    }
}
